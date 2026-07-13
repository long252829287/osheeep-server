package com.osheeep.server.user;

import com.osheeep.server.auth.wechat.WechatCode2SessionClient;
import com.osheeep.server.auth.wechat.WechatSession;
import org.springframework.stereotype.Service;

@Service
public class AccountDeletionService {

    private final WechatCode2SessionClient sessionClient;
    private final AccountDeletionTransaction deletionTransaction;

    public AccountDeletionService(
            WechatCode2SessionClient sessionClient,
            AccountDeletionTransaction deletionTransaction
    ) {
        this.sessionClient = sessionClient;
        this.deletionTransaction = deletionTransaction;
    }

    public void deleteAccount(Long userId, String code) {
        WechatSession session = sessionClient.exchange(code);
        deletionTransaction.deleteVerified(userId, session.openid());
    }
}
