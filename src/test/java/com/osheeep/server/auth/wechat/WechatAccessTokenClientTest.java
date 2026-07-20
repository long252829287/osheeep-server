package com.osheeep.server.auth.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class WechatAccessTokenClientTest {

    private static final String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token"
            + "?grant_type=client_credential&appid=app-id&secret=app-secret";

    private MockRestServiceServer server;
    private MutableClock clock;
    private WechatAccessTokenClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock(Instant.parse("2026-07-16T08:00:00Z"));
        client = new WechatAccessTokenClient(
                builder,
                new ObjectMapper(),
                new WechatProperties("app-id", "app-secret"),
                clock);
    }

    @Test
    void cachesTokenUntilFiveMinutesBeforeWechatExpiry() {
        server.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-1\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-2\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.currentToken()).isEqualTo("token-1");
        assertThat(client.currentToken()).isEqualTo("token-1");
        clock.advance(Duration.ofSeconds(6899));
        assertThat(client.currentToken()).isEqualTo("token-1");
        clock.advance(Duration.ofSeconds(1));
        assertThat(client.currentToken()).isEqualTo("token-2");
        server.verify();
    }

    @Test
    void usesSixtySecondMinimumBeforeRefreshingShortExpiry() {
        server.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"access_token\":\"short-token\",\"expires_in\":120}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"access_token\":\"fresh-token\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.currentToken()).isEqualTo("short-token");
        clock.advance(Duration.ofSeconds(59));
        assertThat(client.currentToken()).isEqualTo("short-token");
        clock.advance(Duration.ofSeconds(1));
        assertThat(client.currentToken()).isEqualTo("fresh-token");
        server.verify();
    }

    @Test
    void invalidatesOnlyTheMatchingCachedToken() {
        server.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-1\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-2\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.currentToken()).isEqualTo("token-1");
        client.invalidate("different-token");
        assertThat(client.currentToken()).isEqualTo("token-1");
        client.invalidate("token-1");
        assertThat(client.currentToken()).isEqualTo("token-2");
        server.verify();
    }

    @Test
    void tokenFailureDoesNotExposeWechatBodyOrSecret(CapturedOutput output) {
        server.expect(anything()).andRespond(withSuccess(
                "{\"errcode\":40013,\"errmsg\":\"invalid appid secret-value\"}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::currentToken)
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.errorCode())
                            .isEqualTo(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE);
                    assertThat(error.getMessage())
                            .isEqualTo("Dinner recipe moderation is temporarily unavailable")
                            .doesNotContain("secret-value", "app-secret");
                });
        assertThat(output).doesNotContain(
                "secret-value", "app-secret", "invalid appid", "40013");
        server.verify();
    }

    @Test
    void tokenTransportFailureMapsToUnavailableWithoutLoggingRequestDetails(
            CapturedOutput output
    ) {
        server.expect(requestTo(TOKEN_URL)).andRespond(withServerError());

        assertThatThrownBy(client::currentToken)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE));
        assertThat(output).doesNotContain("app-secret", TOKEN_URL);
        server.verify();
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
