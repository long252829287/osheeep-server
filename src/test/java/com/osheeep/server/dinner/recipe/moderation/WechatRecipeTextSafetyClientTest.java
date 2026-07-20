package com.osheeep.server.dinner.recipe.moderation;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.auth.wechat.WechatAccessTokenProvider;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WechatRecipeTextSafetyClientTest {

    private static final String CHECK_URL =
            "https://api.weixin.qq.com/wxa/msg_sec_check?access_token=";

    @Mock private WechatAccessTokenProvider tokenProvider;

    private MockRestServiceServer server;
    private WechatRecipeTextSafetyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WechatRecipeTextSafetyClient(
                builder, new ObjectMapper(), tokenProvider);
    }

    @Test
    void sendsVersionTwoForumCheckAndAcceptsOnlyPass() {
        String checkedContent = "口味：酸甜\n做法：家常炒\n1. 切番茄";
        when(tokenProvider.currentToken()).thenReturn("token-1");
        server.expect(once(), requestTo(CHECK_URL + "token-1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"openid":"openid-7","scene":3,"version":2,
                         "title":"番茄炒蛋","content":"口味：酸甜\\n做法：家常炒\\n1. 切番茄"}
                        """))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"result\":{\"suggest\":\"pass\","
                                + "\"label\":100},\"trace_id\":\"trace-1\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.check("openid-7", "番茄炒蛋", checkedContent))
                .isEqualTo(RecipeTextSafetyResult.PASS);
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
        assertThat(client.check("openid-7", "菜名", "内容"))
                .isEqualTo(RecipeTextSafetyResult.REJECT);
        assertThat(client.check("openid-7", "菜名", "内容"))
                .isEqualTo(RecipeTextSafetyResult.REJECT);
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

        assertThat(client.check("openid-7", "菜名", "内容"))
                .isEqualTo(RecipeTextSafetyResult.PASS);
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

        assertThatThrownBy(() -> client.check("openid-7", "菜名", "内容"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE));
        verify(tokenProvider, times(2)).currentToken();
        server.verify();
    }

    @Test
    void otherWechatErrorFailsClosedWithoutRetryOrPlatformDetails(CapturedOutput output) {
        when(tokenProvider.currentToken()).thenReturn("token-secret");
        server.expect(requestTo(CHECK_URL + "token-secret"))
                .andRespond(withSuccess(
                        "{\"errcode\":45009,\"errmsg\":\"platform-secret-detail\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.check("openid-secret", "title-secret", "content-secret"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.errorCode())
                            .isEqualTo(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE);
                    assertThat(error.getMessage())
                            .doesNotContain("45009", "platform-secret-detail");
                });
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
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE));
        assertThat(output).doesNotContain(
                "token-secret", "openid-secret", "title-secret",
                "content-secret", "response-secret");
        server.verify();
    }

    @Test
    void moderationErrorsUseTheRequiredStableHttpContracts() {
        assertThat(ErrorCode.DINNER_RECIPE_CONTENT_REJECTED.httpStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ErrorCode.DINNER_RECIPE_CONTENT_REJECTED.defaultMessage())
                .isEqualTo("Dinner recipe content was rejected");
        assertThat(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE.httpStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE.defaultMessage())
                .isEqualTo("Dinner recipe moderation is temporarily unavailable");
    }
}
