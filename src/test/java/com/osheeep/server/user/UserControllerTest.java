package com.osheeep.server.user;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestUserMapperConfig.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AccountDeletionService accountDeletionService;

    @Test
    void deletionRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/users/me/deletion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"fresh-code\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void deletionRejectsBlankWechatCode() throws Exception {
        mockMvc.perform(post("/api/users/me/deletion")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void deletionUsesCurrentUserAndReturnsSuccess() throws Exception {
        mockMvc.perform(post("/api/users/me/deletion")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"fresh-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(accountDeletionService).deleteAccount(7L, "fresh-code");
    }

    @Test
    void deletionMapsWechatIdentityMismatch() throws Exception {
        doThrow(new BusinessException(ErrorCode.ACCOUNT_DELETION_IDENTITY_MISMATCH))
                .when(accountDeletionService).deleteAccount(7L, "other-account-code");

        mockMvc.perform(post("/api/users/me/deletion")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"other-account-code\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode")
                        .value("ACCOUNT_DELETION_IDENTITY_MISMATCH"));
    }

    private String token() {
        return jwtService.generateToken(new CurrentUser(7L, "wx_user"));
    }
}
