package com.osheeep.server.dinner.ingredient;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.dinner.ingredient.dto.IngredientResponse;
import com.osheeep.server.dinner.ingredient.dto.InventoryItemResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
class DinnerIngredientControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private DinnerIngredientService ingredientService;

    private String token;

    @BeforeEach
    void setUp() {
        reset(ingredientService);
        token = jwtService.generateToken(new CurrentUser(7L, "wx_user"));
    }

    @Test
    void ingredientEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/dinner/ingredients"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void listsIngredientsAndHouseholdInventory() throws Exception {
        when(ingredientService.listIngredients(7L)).thenReturn(List.of(
                new IngredientResponse(3L, "鸡蛋", "蛋奶", "枚")));
        when(ingredientService.listInventory(7L)).thenReturn(List.of(new InventoryItemResponse(
                3L, "鸡蛋", "蛋奶", new BigDecimal("6.000"), "枚", 2L, 8L,
                Instant.parse("2026-07-15T06:30:00Z"))));

        mockMvc.perform(authenticated(get("/api/dinner/ingredients")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("鸡蛋"))
                .andExpect(jsonPath("$.data[0].defaultUnit").value("枚"));
        mockMvc.perform(authenticated(get("/api/dinner/inventory")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].quantity").value(6.000))
                .andExpect(jsonPath("$.data[0].updatedAt").value("2026-07-15T06:30:00Z"));
    }

    @Test
    void upsertsInventoryForAuthenticatedUser() throws Exception {
        when(ingredientService.upsertInventoryItem(
                7L, 3L, new BigDecimal("8.000"), " 枚 ", 2L))
                .thenReturn(new InventoryItemResponse(
                        3L, "鸡蛋", "蛋奶", new BigDecimal("8.000"), "枚", 3L, 7L, null));

        mockMvc.perform(authenticated(put("/api/dinner/inventory/3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":8.000,\"unit\":\" 枚 \",\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ingredientId").value(3))
                .andExpect(jsonPath("$.data.unit").value("枚"))
                .andExpect(jsonPath("$.data.version").value(3));
    }

    @Test
    void permitsNullQuantityButRejectsInvalidUnitAndVersion() throws Exception {
        when(ingredientService.upsertInventoryItem(7L, 3L, null, "枚", 0L))
                .thenReturn(new InventoryItemResponse(3L, "鸡蛋", "蛋奶", null, "枚", 1L, 7L, null));

        mockMvc.perform(authenticated(put("/api/dinner/inventory/3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":null,\"unit\":\"枚\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(authenticated(put("/api/dinner/inventory/3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":-0.001,\"unit\":\"   \",\"version\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        mockMvc.perform(authenticated(put("/api/dinner/inventory/3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"unit\":\"12345678901234567\",\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsQuantityOutsideInventoryDecimalPrecision() throws Exception {
        mockMvc.perform(authenticated(put("/api/dinner/inventory/3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0.0001,\"unit\":\"枚\",\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        mockMvc.perform(authenticated(put("/api/dinner/inventory/3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1000000000.000,\"unit\":\"枚\",\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        verifyNoInteractions(ingredientService);
    }

    @Test
    void deletesInventoryWithQueryVersion() throws Exception {
        mockMvc.perform(authenticated(delete("/api/dinner/inventory/3").queryParam("version", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void mapsInventoryVersionConflict() throws Exception {
        when(ingredientService.upsertInventoryItem(
                7L, 3L, new BigDecimal("8.000"), "枚", 2L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_INVENTORY_VERSION_CONFLICT));

        mockMvc.perform(authenticated(put("/api/dinner/inventory/3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":8.000,\"unit\":\"枚\",\"version\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DINNER_INVENTORY_VERSION_CONFLICT"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
    ) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
