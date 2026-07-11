package com.osheeep.server.auth.wechat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "osheeep.wechat")
public record WechatProperties(String appId, String appSecret) {
}
