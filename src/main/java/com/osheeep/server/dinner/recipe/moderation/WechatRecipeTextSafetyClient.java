package com.osheeep.server.dinner.recipe.moderation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.osheeep.server.auth.wechat.WechatAccessTokenProvider;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class WechatRecipeTextSafetyClient implements RecipeTextSafetyGateway {

    private static final int INVALID_ACCESS_TOKEN = 40001;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final WechatAccessTokenProvider tokenProvider;

    public WechatRecipeTextSafetyClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            WechatAccessTokenProvider tokenProvider
    ) {
        this.restClient = builder.baseUrl("https://api.weixin.qq.com").build();
        this.objectMapper = objectMapper;
        this.tokenProvider = tokenProvider;
    }

    @Override
    public RecipeTextSafetyResult check(String openid, String title, String content) {
        String token = tokenProvider.currentToken();
        CheckResponse response = requestCheck(token, openid, title, content);
        if (isInvalidToken(response)) {
            tokenProvider.invalidate(token);
            String refreshedToken = tokenProvider.currentToken();
            CheckResponse retried = requestCheck(refreshedToken, openid, title, content);
            if (isInvalidToken(retried)) {
                tokenProvider.invalidate(refreshedToken);
            }
            return mapResult(retried);
        }
        return mapResult(response);
    }

    private CheckResponse requestCheck(
            String token,
            String openid,
            String title,
            String content
    ) {
        try {
            String responseBody = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/wxa/msg_sec_check")
                            .queryParam("access_token", token)
                            .build())
                    .body(new CheckRequest(openid, 3, 2, title, content))
                    .retrieve()
                    .body(String.class);
            if (responseBody == null || responseBody.isBlank()) {
                throw unavailable();
            }
            return objectMapper.readValue(responseBody, CheckResponse.class);
        } catch (RestClientException | JsonProcessingException exception) {
            throw unavailable();
        }
    }

    private RecipeTextSafetyResult mapResult(CheckResponse response) {
        if (response == null || response.errcode() == null || response.errcode() != 0) {
            throw unavailable();
        }
        String suggest = response.result() == null ? null : response.result().suggest();
        if ("pass".equals(suggest)) {
            return RecipeTextSafetyResult.PASS;
        }
        if ("review".equals(suggest) || "risky".equals(suggest)) {
            return RecipeTextSafetyResult.REJECT;
        }
        throw unavailable();
    }

    private boolean isInvalidToken(CheckResponse response) {
        return response != null && Integer.valueOf(INVALID_ACCESS_TOKEN).equals(response.errcode());
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE);
    }

    private record CheckRequest(
            String openid,
            int scene,
            int version,
            String title,
            String content
    ) {

        @Override
        public String toString() {
            return "CheckRequest[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CheckResponse(Integer errcode, Result result, String errmsg) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Result(String suggest, Integer label) {
    }
}
