package com.osheeep.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthControllerTest.MockMapperConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        reset(userMapper);
    }

    @Test
    void registerCreatesUserWithEncodedPasswordAndReturnsToken() throws Exception {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(userMapper.insert(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(100L);
            return 1;
        });

        String body = "{\"email\":\"new@example.com\",\"username\":\"newuser\","
                + "\"password\":\"password123\",\"displayName\":\"New User\"}";

        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.user.id").value(100))
                .andExpect(jsonPath("$.data.user.email").value("new@example.com"))
                .andExpect(jsonPath("$.data.user.username").value("newuser"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode root = objectMapper.readTree(response);
        assertThat(root.path("data").path("accessToken").asText()).isNotBlank();

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(userCaptor.capture());
        UserEntity inserted = userCaptor.getValue();
        assertThat(inserted.getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", inserted.getPasswordHash())).isTrue();
    }

    @Test
    void registerRejectsDuplicateEmail() throws Exception {
        when(userMapper.selectOne(any())).thenReturn(user(1L, "taken@example.com", "taken", "hash"));

        String body = "{\"email\":\"taken@example.com\",\"username\":\"taken\","
                + "\"password\":\"password123\"}";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_ERROR"));
    }

    @Test
    void registerRejectsDuplicateUsername() throws Exception {
        when(userMapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(user(1L, "other@example.com", "taken", "hash"));

        String body = "{\"email\":\"new@example.com\",\"username\":\"taken\","
                + "\"password\":\"password123\"}";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_ERROR"));
    }

    @Test
    void loginReturnsTokenAndUser() throws Exception {
        UserEntity existing = user(42L, "long@example.com", "long", passwordEncoder.encode("password123"));
        when(userMapper.selectOne(any())).thenReturn(existing);

        String body = "{\"email\":\"long@example.com\",\"password\":\"password123\"}";

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.user.id").value(42))
                .andExpect(jsonPath("$.data.user.email").value("long@example.com"))
                .andExpect(jsonPath("$.data.user.username").value("long"));
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        UserEntity existing = user(42L, "long@example.com", "long", passwordEncoder.encode("password123"));
        when(userMapper.selectOne(any())).thenReturn(existing);

        String body = "{\"email\":\"long@example.com\",\"password\":\"wrong-password\"}";

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void meReturnsCurrentUser() throws Exception {
        UserEntity existing = user(42L, "long@example.com", "long", "hash");
        when(userMapper.selectById(42L)).thenReturn(existing);
        String token = jwtService.generateToken(new CurrentUser(42L, "long"));

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.email").value("long@example.com"))
                .andExpect(jsonPath("$.data.username").value("long"));
    }

    @Test
    void logoutReturnsSuccessForAuthenticatedUser() throws Exception {
        String token = jwtService.generateToken(new CurrentUser(42L, "long"));

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private UserEntity user(Long id, String email, String username, String passwordHash) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setEmail(email);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPasswordHash(passwordHash);
        user.setStatus("ACTIVE");
        return user;
    }

    @TestConfiguration
    static class MockMapperConfig {
        @Bean
        UserMapper userMapper() {
            return mock(UserMapper.class);
        }
    }
}
