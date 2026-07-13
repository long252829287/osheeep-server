package com.osheeep.server.user;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.user.dto.AccountDeletionRequest;
import com.osheeep.server.user.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;
    private final AccountDeletionService accountDeletionService;

    public UserController(UserService userService, AccountDeletionService accountDeletionService) {
        this.userService = userService;
        this.accountDeletionService = accountDeletionService;
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> me(@AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(UserProfileResponse.from(userService.requireActiveUser(currentUser.id())));
    }

    @PostMapping("/me/deletion")
    public ApiResponse<Void> deleteMe(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody AccountDeletionRequest request
    ) {
        accountDeletionService.deleteAccount(currentUser.id(), request.code());
        return ApiResponse.ok(null);
    }
}
