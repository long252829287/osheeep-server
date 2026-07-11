package com.osheeep.server.auth;

import com.osheeep.server.auth.dto.LoginRequest;
import com.osheeep.server.auth.dto.LoginResponse;
import com.osheeep.server.auth.dto.RegisterRequest;
import com.osheeep.server.auth.dto.WechatLoginRequest;
import com.osheeep.server.auth.wechat.WechatAuthService;
import com.osheeep.server.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final WechatAuthService wechatAuthService;

    public AuthController(AuthService authService, WechatAuthService wechatAuthService) {
        this.authService = authService;
        this.wechatAuthService = wechatAuthService;
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/wechat")
    public ApiResponse<LoginResponse> wechatLogin(@Valid @RequestBody WechatLoginRequest request) {
        return ApiResponse.ok(wechatAuthService.login(request.code()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        return ApiResponse.ok(null);
    }
}
