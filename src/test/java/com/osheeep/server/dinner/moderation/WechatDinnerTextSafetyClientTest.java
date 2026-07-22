package com.osheeep.server.dinner.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.auth.wechat.WechatAccessTokenProvider;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WechatDinnerTextSafetyClientTest {

    private static final String CHECK_URL =
            "https://api.weixin.qq.com/wxa/msg_sec_check?access_token=";

    @Mock private WechatAccessTokenProvider tokenProvider;

    private MockRestServiceServer server;
    private WechatDinnerTextSafetyClient client;
    private Logger defaultRestClientLogger;
    private Level previousDefaultRestClientLevel;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WechatDinnerTextSafetyClient(
                builder, new ObjectMapper(), tokenProvider);
        defaultRestClientLogger = (Logger) LoggerFactory.getLogger(
                "org.springframework.web.client.DefaultRestClient");
        previousDefaultRestClientLevel = defaultRestClientLogger.getLevel();
    }

    @AfterEach
    void restoreDefaultRestClientLogLevel() {
        defaultRestClientLogger.setLevel(previousDefaultRestClientLevel);
    }

    @Test
    void sendsVersionTwoForumCheckAndAcceptsOnlyPass() {
        String checkedContent = "家庭名称：我们的小家";
        when(tokenProvider.currentToken()).thenReturn("token-1");
        server.expect(once(), requestTo(CHECK_URL + "token-1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"openid":"openid-7","scene":3,"version":2,
                         "title":"家庭名称","content":"家庭名称：我们的小家"}
                        """))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"result\":{\"suggest\":\"pass\","
                                + "\"label\":100},\"trace_id\":\"trace-1\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.check("openid-7", "家庭名称", checkedContent))
                .isEqualTo(DinnerTextSafetyResult.PASS);
        server.verify();
    }

    @Test
    void reviewAndRiskyAreRejectedWithoutReturningPlatformDetails() {
        when(tokenProvider.currentToken()).thenReturn("token-1");
        server.expect(requestTo(CHECK_URL + "token-1"))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"result\":{\"suggest\":\"review\","
                                + "\"label\":21000}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(CHECK_URL + "token-1"))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"result\":{\"suggest\":\"risky\","
                                + "\"label\":20006}}",
                        MediaType.APPLICATION_JSON));
        assertThat(client.check("openid-7", "标题", "内容"))
                .isEqualTo(DinnerTextSafetyResult.REJECT);
        assertThat(client.check("openid-7", "标题", "内容"))
                .isEqualTo(DinnerTextSafetyResult.REJECT);
        server.verify();
    }

    @Test
    void invalidTokenIsInvalidatedAndRetriedExactlyOnce() {
        when(tokenProvider.currentToken()).thenReturn("stale-token", "fresh-token");
        server.expect(requestTo(CHECK_URL + "stale-token"))
                .andRespond(withSuccess(
                        "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(CHECK_URL + "fresh-token"))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"result\":{\"suggest\":\"pass\","
                                + "\"label\":100}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.check("openid-7", "标题", "内容"))
                .isEqualTo(DinnerTextSafetyResult.PASS);
        verify(tokenProvider).invalidate("stale-token");
        verify(tokenProvider, times(2)).currentToken();
        server.verify();
    }

    @Test
    void secondInvalidTokenFailsClosedWithoutAThirdRequest() {
        when(tokenProvider.currentToken()).thenReturn("stale-token", "still-invalid-token");
        server.expect(requestTo(CHECK_URL + "stale-token"))
                .andRespond(withSuccess(
                        "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(CHECK_URL + "still-invalid-token"))
                .andRespond(withSuccess(
                        "{\"errcode\":40001,\"errmsg\":\"invalid credential again\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.check("openid-7", "标题", "内容"))
                .isInstanceOf(DinnerTextSafetyUnavailableException.class);
        verify(tokenProvider, times(2)).currentToken();
        verify(tokenProvider).invalidate("still-invalid-token");
        server.verify();
    }

    @Test
    void legacyTokenProviderBusinessErrorMapsToGenericUnavailable() {
        when(tokenProvider.currentToken()).thenThrow(
                new BusinessException(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE));

        assertThatThrownBy(() -> client.check(
                "openid-secret", "title-secret", "content-secret"))
                .isInstanceOf(DinnerTextSafetyUnavailableException.class)
                .hasMessage("Dinner text safety is temporarily unavailable")
                .hasMessageNotContaining("recipe");
    }

    @Test
    void otherWechatErrorFailsClosedWithoutRetryOrPlatformDetails(CapturedOutput output) {
        when(tokenProvider.currentToken()).thenReturn("token-secret");
        server.expect(requestTo(CHECK_URL + "token-secret"))
                .andRespond(withSuccess(
                        "{\"errcode\":45009,\"errmsg\":\"platform-secret-detail\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.check("openid-secret", "title-secret", "content-secret"))
                .isInstanceOf(DinnerTextSafetyUnavailableException.class)
                .hasMessageNotContaining("45009")
                .hasMessageNotContaining("platform-secret-detail");
        verify(tokenProvider, times(1)).currentToken();
        verify(tokenProvider, never()).invalidate("token-secret");
        assertThat(output).doesNotContain(
                "token-secret", "openid-secret", "title-secret",
                "content-secret", "platform-secret-detail", "45009");
        server.verify();
    }

    @Test
    void transportFailureMapsToUnavailableWithoutSensitiveLogs(CapturedOutput output) {
        when(tokenProvider.currentToken()).thenReturn("token-secret");
        server.expect(requestTo(CHECK_URL + "token-secret"))
                .andRespond(withServerError()
                        .body("response-secret")
                        .contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.check("openid-secret", "title-secret", "content-secret"))
                .isInstanceOf(DinnerTextSafetyUnavailableException.class);
        assertThat(output).doesNotContain(
                "token-secret", "openid-secret", "title-secret",
                "content-secret", "response-secret");
        server.verify();
    }

    @Test
    void debugRequestLogsDoNotExposeInspectedDinnerText(CapturedOutput output) {
        String openid = "openid-debug-secret";
        String title = "title-debug-secret";
        String checkedContent = "content-debug-secret";
        when(tokenProvider.currentToken()).thenReturn("token-1");
        server.expect(requestTo(CHECK_URL + "token-1"))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"result\":{\"suggest\":\"pass\"}}",
                        MediaType.APPLICATION_JSON));

        defaultRestClientLogger.setLevel(Level.DEBUG);

        assertThat(client.check(openid, title, checkedContent))
                .isEqualTo(DinnerTextSafetyResult.PASS);
        assertThat(output).doesNotContain(openid, title, checkedContent);
        server.verify();
    }
}
