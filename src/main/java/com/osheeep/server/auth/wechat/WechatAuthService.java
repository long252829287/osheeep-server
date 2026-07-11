package com.osheeep.server.auth.wechat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.auth.AuthService;
import com.osheeep.server.auth.dto.LoginResponse;
import com.osheeep.server.user.UserService;
import com.osheeep.server.user.entity.UserEntity;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WechatAuthService {

    private final WechatCode2SessionClient sessionClient;
    private final WechatUserIdentityMapper identityMapper;
    private final UserService userService;
    private final AuthService authService;

    public WechatAuthService(
            WechatCode2SessionClient sessionClient,
            WechatUserIdentityMapper identityMapper,
            UserService userService,
            AuthService authService
    ) {
        this.sessionClient = sessionClient;
        this.identityMapper = identityMapper;
        this.userService = userService;
        this.authService = authService;
    }

    @Transactional
    public LoginResponse login(String code) {
        WechatSession session = sessionClient.exchange(code);
        WechatUserIdentityEntity identity = identityMapper.selectOne(
                Wrappers.lambdaQuery(WechatUserIdentityEntity.class)
                        .eq(WechatUserIdentityEntity::getOpenid, session.openid())
                        .last("LIMIT 1")
        );
        UserEntity user = identity == null
                ? createUser(session.openid())
                : userService.requireActiveUser(identity.getUserId());
        return authService.issueToken(user);
    }

    private UserEntity createUser(String openid) {
        String username = "wx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        UserEntity user = userService.createWechatUser(username);
        WechatUserIdentityEntity identity = new WechatUserIdentityEntity();
        identity.setUserId(user.getId());
        identity.setOpenid(openid);
        identityMapper.insert(identity);
        return user;
    }
}
