package com.osheeep.server.user;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.user.dto.UserProfileResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> me(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(UserProfileResponse.from(userService.requireActiveUser(currentUser.id())));
    }
}
