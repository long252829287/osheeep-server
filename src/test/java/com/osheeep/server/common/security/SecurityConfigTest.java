package com.osheeep.server.common.security;

import com.osheeep.server.OsheeepServerApplication;
import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.api.RequestIdFilter;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(classes = OsheeepServerApplication.class)
@AutoConfigureMockMvc
@Import({SecurityConfigTest.TestController.class, TestUserMapperConfig.class})
class SecurityConfigTest {

    private static final String REQUEST_ID = "security-request-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        reset(userMapper);
    }

    @Test
    void authEndpointsArePublic() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, REQUEST_ID)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));

        mockMvc.perform(post("/api/auth/login")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, REQUEST_ID)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void healthEndpointIsPublicButOtherActuatorEndpointsAreDenied() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void protectedApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/protected")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, REQUEST_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, REQUEST_ID))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void protectedApiAcceptsBearerTokenAndExposesCurrentUser() throws Exception {
        String token = jwtService.generateToken(new CurrentUser(42L, "long"));

        mockMvc.perform(get("/api/protected")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, REQUEST_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("42:long"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void protectedApiRejectsTokenWhenUserWasDeleted() throws Exception {
        UserEntity deleted = new UserEntity();
        deleted.setId(42L);
        deleted.setUsername("deleted_user_42");
        deleted.setStatus("DELETED");
        deleted.setDeletedAt(LocalDateTime.parse("2026-07-13T12:00:00"));
        when(userMapper.selectById(42L)).thenReturn(deleted);
        String token = jwtService.generateToken(new CurrentUser(42L, "long"));

        mockMvc.perform(get("/api/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void malformedBearerTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/protected")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, REQUEST_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void corsAllowsLocalFrontendOrigin() throws Exception {
        mockMvc.perform(options("/api/protected")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    @RestController
    @RequestMapping("/api")
    static class TestController {

        @GetMapping("/protected")
        ApiResponse<String> protectedEndpoint(@AuthenticationPrincipal CurrentUser currentUser) {
            return ApiResponse.ok(currentUser.id() + ":" + currentUser.username());
        }
    }

}
