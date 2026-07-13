package com.osheeep.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.auth.wechat.WechatCode2SessionClient;
import com.osheeep.server.auth.wechat.WechatSession;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {

    @Mock
    private WechatCode2SessionClient sessionClient;

    @Mock
    private AccountDeletionTransaction deletionTransaction;

    @Test
    void exchangesFreshCodeBeforeStartingDeletionTransaction() {
        when(sessionClient.exchange("fresh-code"))
                .thenReturn(new WechatSession("openid-7"));
        AccountDeletionService service =
                new AccountDeletionService(sessionClient, deletionTransaction);

        service.deleteAccount(7L, "fresh-code");

        verify(deletionTransaction).deleteVerified(7L, "openid-7");
    }

    @Test
    void failedWechatExchangeNeverStartsDeletionTransaction() {
        when(sessionClient.exchange("used-code"))
                .thenThrow(new BusinessException(ErrorCode.WECHAT_LOGIN_FAILED));
        AccountDeletionService service =
                new AccountDeletionService(sessionClient, deletionTransaction);

        assertThatThrownBy(() -> service.deleteAccount(7L, "used-code"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.WECHAT_LOGIN_FAILED));
        verifyNoInteractions(deletionTransaction);
    }
}
