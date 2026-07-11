package com.osheeep.server.auth.wechat;

public interface WechatCode2SessionClient {

    WechatSession exchange(String code);
}
