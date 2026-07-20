package com.osheeep.server.auth.wechat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class WechatAccessTokenClient implements WechatAccessTokenProvider {

    private static final long REFRESH_EARLY_SECONDS = 300;
    private static final long MINIMUM_CACHE_SECONDS = 60;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final WechatProperties properties;
    private final Clock clock;
    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    @Autowired
    public WechatAccessTokenClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            WechatProperties properties
    ) {
        this(builder, objectMapper, properties, Clock.systemUTC());
    }

    WechatAccessTokenClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            WechatProperties properties,
            Clock clock
    ) {
        this.restClient = builder.baseUrl("https://api.weixin.qq.com").build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String currentToken() {
        CachedToken current = cachedToken.get();
        Instant now = clock.instant();
        if (isFresh(current, now)) {
            return current.value();
        }
        return refreshAfterCacheMiss();
    }

    @Override
    public void invalidate(String token) {
        CachedToken current = cachedToken.get();
        if (current != null && current.value().equals(token)) {
            cachedToken.compareAndSet(current, null);
        }
    }

    private synchronized String refreshAfterCacheMiss() {
        CachedToken current = cachedToken.get();
        Instant now = clock.instant();
        if (isFresh(current, now)) {
            return current.value();
        }

        TokenResponse response = requestToken();
        long cacheSeconds = Math.max(
                MINIMUM_CACHE_SECONDS,
                response.expiresIn() - REFRESH_EARLY_SECONDS);
        CachedToken refreshed = new CachedToken(
                response.accessToken(), now.plusSeconds(cacheSeconds));
        cachedToken.set(refreshed);
        return refreshed.value();
    }

    private TokenResponse requestToken() {
        try {
            String responseBody = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/cgi-bin/token")
                            .queryParam("grant_type", "client_credential")
                            .queryParam("appid", properties.appId())
                            .queryParam("secret", properties.appSecret())
                            .build())
                    .retrieve()
                    .body(String.class);
            if (responseBody == null || responseBody.isBlank()) {
                throw unavailable();
            }
            TokenResponse response = objectMapper.readValue(responseBody, TokenResponse.class);
            if (response == null
                    || response.errcode() != null
                    || response.accessToken() == null
                    || response.accessToken().isBlank()
                    || response.expiresIn() == null) {
                throw unavailable();
            }
            return response;
        } catch (RestClientException | JsonProcessingException exception) {
            throw unavailable();
        }
    }

    private boolean isFresh(CachedToken token, Instant now) {
        return token != null && now.isBefore(token.refreshAt());
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE);
    }

    private record CachedToken(String value, Instant refreshAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn,
            Integer errcode,
            String errmsg
    ) {
    }
}
