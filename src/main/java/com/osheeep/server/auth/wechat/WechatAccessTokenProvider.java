package com.osheeep.server.auth.wechat;

public interface WechatAccessTokenProvider {

    String currentToken();

    void invalidate(String token);
}
