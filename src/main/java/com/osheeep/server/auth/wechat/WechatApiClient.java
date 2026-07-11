package com.osheeep.server.auth.wechat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class WechatApiClient implements WechatCode2SessionClient {

    private static final Logger log = LoggerFactory.getLogger(WechatApiClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final WechatProperties properties;

    public WechatApiClient(RestClient.Builder builder, ObjectMapper objectMapper, WechatProperties properties) {
        this.restClient = builder.baseUrl("https://api.weixin.qq.com").build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public WechatSession exchange(String code) {
        try {
            String responseBody = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/sns/jscode2session")
                            .queryParam("appid", properties.appId())
                            .queryParam("secret", properties.appSecret())
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(String.class);
            if (responseBody == null || responseBody.isBlank()) {
                throw loginFailed();
            }
            Code2SessionResponse response = objectMapper.readValue(responseBody, Code2SessionResponse.class);
            if (response != null && response.errcode() != null && response.errcode() != 0) {
                log.warn("Wechat code2session rejected request with errcode={}", response.errcode());
                throw loginFailed();
            }
            if (response == null
                    || response.openid() == null
                    || response.openid().isBlank()) {
                throw loginFailed();
            }
            return new WechatSession(response.openid());
        } catch (RestClientException | JsonProcessingException exception) {
            log.warn("Wechat code2session request failed with exception={}",
                    exception.getClass().getSimpleName());
            throw loginFailed();
        }
    }

    private BusinessException loginFailed() {
        return new BusinessException(ErrorCode.WECHAT_LOGIN_FAILED);
    }

    private record Code2SessionResponse(
            String openid,
            @JsonProperty("session_key") String sessionKey,
            Integer errcode,
            String errmsg
    ) {
    }
}
