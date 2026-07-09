package com.osheeep.server.common.error;

import com.osheeep.server.OsheeepServerApplication;
import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.api.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(classes = OsheeepServerApplication.class)
@AutoConfigureMockMvc
@Import({
        GlobalExceptionHandlerTest.TestController.class,
        GlobalExceptionHandlerTest.TestSecurityConfig.class,
        TestUserMapperConfig.class
})
class GlobalExceptionHandlerTest {

    private static final String REQUEST_ID = "request-123";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validationFailureReturnsUnifiedResponse() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, REQUEST_ID))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void missingRequestBodyReturnsValidationError() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void unauthenticatedRequestReturnsUnifiedResponse() throws Exception {
        mockMvc.perform(get("/test/unauthenticated")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, REQUEST_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void businessExceptionReturnsItsErrorCodeAndMessage() throws Exception {
        mockMvc.perform(get("/test/business")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, REQUEST_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_ERROR"))
                .andExpect(jsonPath("$.message").value("cluster already exists"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void successResponseContainsRequestId() throws Exception {
        mockMvc.perform(get("/test/success")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("ok"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @PostMapping("/validation")
        ApiResponse<String> validation(@Valid @RequestBody ValidationRequest request) {
            return ApiResponse.ok(request.name());
        }

        @GetMapping("/unauthenticated")
        ApiResponse<String> unauthenticated() {
            throw new AuthenticationCredentialsNotFoundException("Full authentication is required");
        }

        @GetMapping("/business")
        ApiResponse<String> business() {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "cluster already exists");
        }

        @GetMapping("/success")
        ApiResponse<String> success() {
            return ApiResponse.ok("ok");
        }
    }

    record ValidationRequest(@NotBlank String name) {
    }

    @TestConfiguration
    static class TestSecurityConfig {

        @Bean
        @Order(0)
        SecurityFilterChain testEndpoints(HttpSecurity http) throws Exception {
            return http
                    .securityMatcher("/test/**")
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .build();
        }
    }
}
