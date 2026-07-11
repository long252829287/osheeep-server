package com.osheeep.server.auth.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class WechatApiClientTest {

    private MockRestServiceServer server;
    private WechatApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new WechatApiClient(
                builder,
                new ObjectMapper(),
                new WechatProperties("app-id", "app-secret")
        );
    }

    @Test
    void exchangesCodeWithoutExposingSessionKey() {
        server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session"
                        + "?appid=app-id&secret=app-secret&js_code=code-1&grant_type=authorization_code"))
                .andRespond(withSuccess(
                        "{\"openid\":\"openid-1\",\"session_key\":\"secret-session\"}",
                        MediaType.TEXT_PLAIN
                ));

        assertThat(client.exchange("code-1")).isEqualTo(new WechatSession("openid-1"));
        server.verify();
    }

    @Test
    void mapsWechatErrorWithoutExposingSensitiveResponse(CapturedOutput output) {
        server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session"
                        + "?appid=app-id&secret=app-secret&js_code=bad-code&grant_type=authorization_code"))
                .andRespond(withSuccess(
                        "{\"errcode\":40029,\"errmsg\":\"invalid code\"}",
                        MediaType.APPLICATION_JSON
                ));

        assertThatThrownBy(() -> client.exchange("bad-code"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.WECHAT_LOGIN_FAILED);
                    assertThat(exception.getMessage()).doesNotContain("invalid code");
                });
        assertThat(output).contains("errcode=40029");
        assertThat(output).doesNotContain("invalid code");
        server.verify();
    }

    @Test
    void logsOnlyTransportExceptionType(CapturedOutput output) {
        server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session"
                        + "?appid=app-id&secret=app-secret&js_code=bad-code&grant_type=authorization_code"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> client.exchange("bad-code"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.WECHAT_LOGIN_FAILED));
        assertThat(output).contains("request failed with exception=");
        server.verify();
    }
}
