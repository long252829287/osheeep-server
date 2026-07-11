package com.osheeep.server.auth.wechat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class WechatApiClient implements WechatCode2SessionClient {

    private final RestClient restClient;
    private final WechatProperties properties;

    public WechatApiClient(RestClient.Builder builder, WechatProperties properties) {
        this.restClient = builder.baseUrl("https://api.weixin.qq.com").build();
        this.properties = properties;
    }

    @Override
    public WechatSession exchange(String code) {
        try {
            Code2SessionResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/sns/jscode2session")
                            .queryParam("appid", properties.appId())
                            .queryParam("secret", properties.appSecret())
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(Code2SessionResponse.class);
            if (response == null
                    || response.errcode() != null && response.errcode() != 0
                    || response.openid() == null
                    || response.openid().isBlank()) {
                throw loginFailed();
            }
            return new WechatSession(response.openid());
        } catch (RestClientException exception) {
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
