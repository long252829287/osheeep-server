package com.osheeep.server.dinner.household;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.dinner.household.dto.HouseholdCreatedResponse;
import com.osheeep.server.dinner.household.dto.HouseholdResponse;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
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

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestUserMapperConfig.class)
class DinnerHouseholdControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;

    @MockitoBean
    private DinnerHouseholdService householdService;

    private String token;

    @BeforeEach
    void setUp() {
        reset(householdService);
        token = jwtService.generateToken(new CurrentUser(7L, "wx_user"));
    }

    @Test
    void householdRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/dinner/household"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void currentHouseholdOmitsDataWhenUserIsUnbound() throws Exception {
        when(householdService.current(7L)).thenReturn(null);

        mockMvc.perform(authenticated(get("/api/dinner/household")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void createReturnsInviteCode() throws Exception {
        HouseholdResponse household = new HouseholdResponse(11L, "我们的小家", "Asia/Shanghai", 1);
        when(householdService.create(7L, "我们的小家")).thenReturn(new HouseholdCreatedResponse(
                household, "DINNER 5268", Instant.parse("2026-07-12T06:00:00Z")));

        mockMvc.perform(authenticated(post("/api/dinner/households"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"我们的小家\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.household.id").value(11))
                .andExpect(jsonPath("$.data.inviteCode").value("DINNER 5268"));
    }

    @Test
    void joinReturnsTwoMemberHousehold() throws Exception {
        when(householdService.join(7L, "DINNER 5268"))
                .thenReturn(new HouseholdResponse(11L, "我们的小家", "Asia/Shanghai", 2));

        mockMvc.perform(authenticated(post("/api/dinner/households/join"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"DINNER 5268\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.memberCount").value(2));
    }

    @Test
    void joinMapsInviteError() throws Exception {
        when(householdService.join(7L, "DINNER 0000"))
                .thenThrow(new BusinessException(ErrorCode.DINNER_INVITE_INVALID));

        mockMvc.perform(authenticated(post("/api/dinner/households/join"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"DINNER 0000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("DINNER_INVITE_INVALID"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
    ) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
