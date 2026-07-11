package com.osheeep.server.auth.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.auth.AuthService;
import com.osheeep.server.auth.dto.LoginResponse;
import com.osheeep.server.user.UserService;
import com.osheeep.server.user.dto.UserProfileResponse;
import com.osheeep.server.user.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WechatAuthServiceTest {

    @Mock
    private WechatCode2SessionClient sessionClient;

    @Mock
    private WechatUserIdentityMapper identityMapper;

    @Mock
    private UserService userService;

    @Mock
    private AuthService authService;

    private WechatAuthService service;

    @BeforeEach
    void setUp() {
        service = new WechatAuthService(sessionClient, identityMapper, userService, authService);
    }

    @Test
    void firstLoginCreatesUserAndIdentity() {
        UserEntity created = user(101L, "wx_created");
        LoginResponse expected = response(created);
        when(sessionClient.exchange("code-1")).thenReturn(new WechatSession("openid-1"));
        when(identityMapper.selectOne(any())).thenReturn(null);
        when(userService.createWechatUser(any())).thenReturn(created);
        when(authService.issueToken(created)).thenReturn(expected);

        LoginResponse response = service.login("code-1");

        assertThat(response).isEqualTo(expected);
        verify(identityMapper).insert(argThat((WechatUserIdentityEntity identity) ->
                identity.getUserId().equals(101L) && identity.getOpenid().equals("openid-1")));
    }

    @Test
    void existingIdentityReusesItsActiveUser() {
        WechatUserIdentityEntity identity = new WechatUserIdentityEntity();
        identity.setUserId(42L);
        identity.setOpenid("openid-1");
        UserEntity existing = user(42L, "wx_existing");
        LoginResponse expected = response(existing);
        when(sessionClient.exchange("code-2")).thenReturn(new WechatSession("openid-1"));
        when(identityMapper.selectOne(any())).thenReturn(identity);
        when(userService.requireActiveUser(42L)).thenReturn(existing);
        when(authService.issueToken(existing)).thenReturn(expected);

        assertThat(service.login("code-2")).isEqualTo(expected);

        verify(userService, never()).createWechatUser(any());
        verify(identityMapper, never()).insert(any(WechatUserIdentityEntity.class));
    }

    private UserEntity user(Long id, String username) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername(username);
        user.setStatus("ACTIVE");
        return user;
    }

    private LoginResponse response(UserEntity user) {
        return new LoginResponse("jwt", UserProfileResponse.from(user));
    }
}
