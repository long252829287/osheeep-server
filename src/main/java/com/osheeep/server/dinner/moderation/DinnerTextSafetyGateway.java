package com.osheeep.server.dinner.moderation;

public interface DinnerTextSafetyGateway {

    DinnerTextSafetyResult check(String openid, String title, String content);
}
