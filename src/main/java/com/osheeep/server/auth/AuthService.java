package com.osheeep.server.auth;

import com.osheeep.server.auth.dto.LoginRequest;
import com.osheeep.server.auth.dto.LoginResponse;
import com.osheeep.server.auth.dto.RegisterRequest;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.user.UserService;
import com.osheeep.server.user.dto.UserProfileResponse;
import com.osheeep.server.user.entity.UserEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserService userService, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse register(RegisterRequest request) {
        if (userService.findByEmail(request.email()) != null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "Email already exists");
        }
        if (userService.findByUsername(request.username()) != null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "Username already exists");
        }

        UserEntity user = userService.createUser(
                request.email(),
                request.username(),
                passwordEncoder.encode(request.password()),
                request.displayName()
        );
        return loginResponse(user);
    }

    public LoginResponse login(LoginRequest request) {
        UserEntity user = userService.findByEmail(request.email());
        if (!userService.isActive(user) || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Invalid email or password");
        }
        return loginResponse(user);
    }

    private LoginResponse loginResponse(UserEntity user) {
        String token = jwtService.generateToken(new CurrentUser(user.getId(), user.getUsername()));
        return new LoginResponse(token, UserProfileResponse.from(user));
    }
}
