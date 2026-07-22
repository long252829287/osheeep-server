package com.osheeep.server.dinner.menu;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.dinner.menu.dto.TodayMenuResponse;
import com.osheeep.server.dinner.menu.dto.MenuDishResponse;
import com.osheeep.server.dinner.recipe.DinnerRecipeService;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodSummaryResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeResponse;
import com.osheeep.server.dinner.record.DinnerRecordService;
import com.osheeep.server.dinner.record.dto.CompleteMenuResponse;
import com.osheeep.server.dinner.record.dto.RecordDetailResponse;
import com.osheeep.server.dinner.record.dto.RecordDishResponse;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodStepSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordSummaryResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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
class DinnerMenuControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private DinnerMenuService menuService;
    @MockitoBean private DinnerRecipeService recipeService;
    @MockitoBean private DinnerRecordService recordService;

    private String token;

    @BeforeEach
    void setUp() {
        reset(menuService, recipeService, recordService);
        token = jwtService.generateToken(new CurrentUser(7L, "wx_user"));
    }

    @Test
    void menuEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/dinner/menus/today"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void readsRecipesAndTodayMenu() throws Exception {
        when(recipeService.discover(7L, Set.of(), Set.of(), false)).thenReturn(List.of(
                new RecipeResponse(
                        14L, "番茄炒蛋", "https://www.osheeep.com/media/recipes/family.webp",
                        "家常菜", "酸甜", 10, "HOUSEHOLD", 8L,
                        new RecipeMethodSummaryResponse(21L, "家常做法", "炒"),
                        List.of(), null)));
        when(menuService.today(7L)).thenReturn(todayWithHouseholdDish());

        mockMvc.perform(authenticated(get("/api/dinner/recipes")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("番茄炒蛋"))
                .andExpect(jsonPath("$.data[0].scope").value("HOUSEHOLD"))
                .andExpect(jsonPath("$.data[0].version").value(8))
                .andExpect(jsonPath("$.data[0].defaultMethod.id").value(21))
                .andExpect(jsonPath("$.data[0].defaultMethod.steps").doesNotExist());
        mockMvc.perform(authenticated(get("/api/dinner/menus/today")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.dishes[0].scope").value("HOUSEHOLD"))
                .andExpect(jsonPath("$.data.dishes[0].recipeVersion").value(8))
                .andExpect(jsonPath("$.data.dishes[0].method.name").value("家常做法"))
                .andExpect(jsonPath("$.data.dishes[0].method.steps").doesNotExist());
    }

    @Test
    void todayMapsMissingActiveHouseholdToConflict() throws Exception {
        when(menuService.today(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        mockMvc.perform(authenticated(get("/api/dinner/menus/today")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DINNER_HOUSEHOLD_REQUIRED"));
    }

    @Test
    void todaySerializesThePreMembershipMaskWithoutIdentifiers() throws Exception {
        when(menuService.today(7L)).thenReturn(
                TodayMenuResponse.preMembership(LocalDate.of(2026, 7, 11)));

        mockMvc.perform(authenticated(get("/api/dinner/menus/today")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menuDate").value("2026-07-11"))
                .andExpect(jsonPath("$.data.status").value("PRE_MEMBERSHIP"))
                .andExpect(jsonPath("$.data.historyVisible").value(false))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.recordId").doesNotExist())
                .andExpect(jsonPath("$.data.selectedRecipeIds").doesNotExist())
                .andExpect(jsonPath("$.data.dishes").doesNotExist());
    }

    @Test
    void updatesConfirmsAndCompletesTodayMenu() throws Exception {
        when(menuService.updateSelections(7L, List.of(1L, 2L), 4L))
                .thenReturn(today("DRAFT", 5L, null));
        when(menuService.confirm(7L, 5L, "00000000-0000-4000-8000-000000000021"))
                .thenReturn(today("CONFIRMED", 6L, null));
        when(recordService.complete(7L, 6L, "00000000-0000-4000-8000-000000000022"))
                .thenReturn(new CompleteMenuResponse(91L, today("COMPLETED", 7L, 91L)));

        mockMvc.perform(authenticated(put("/api/dinner/menus/today/selections"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipeIds\":[1,2],\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(5));
        verify(menuService).updateSelections(7L, List.of(1L, 2L), 4L);
        mockMvc.perform(authenticated(post("/api/dinner/menus/today/confirm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":5,\"idempotencyKey\":\"00000000-0000-4000-8000-000000000021\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        mockMvc.perform(authenticated(post("/api/dinner/menus/today/complete"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":6,\"idempotencyKey\":\"00000000-0000-4000-8000-000000000022\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recordId").value(91));
    }

    @Test
    void readsRecordListAndDetail() throws Exception {
        when(recordService.list(7L)).thenReturn(List.of(new RecordSummaryResponse(
                91L, LocalDate.of(2026, 7, 11), 7L,
                Instant.parse("2026-07-11T11:00:00Z"), 1)));
        when(recordService.detail(7L, 91L)).thenReturn(new RecordDetailResponse(
                91L, LocalDate.of(2026, 7, 11), 7L,
                Instant.parse("2026-07-11T11:00:00Z"),
                List.of(new RecordDishResponse(
                        14L, "番茄炒蛋", null, "家常菜", "酸甜", 10, "BOTH",
                        "HOUSEHOLD", 8L, 2,
                        new RecordMethodSnapshotResponse(
                                21L, "家常做法", "炒",
                                List.of(new RecordMethodStepSnapshotResponse("翻炒", 0))),
                        List.of(new RecordIngredientSnapshotResponse(
                                101L, "鸡蛋", new BigDecimal("2.000"), "枚", true, 0))))));

        mockMvc.perform(authenticated(get("/api/dinner/records")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].dishCount").value(1));
        mockMvc.perform(authenticated(get("/api/dinner/records/91")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dishes[0].source").value("BOTH"))
                .andExpect(jsonPath("$.data.dishes[0].scope").value("HOUSEHOLD"))
                .andExpect(jsonPath("$.data.dishes[0].recipeVersion").value(8))
                .andExpect(jsonPath("$.data.dishes[0].servings").value(2))
                .andExpect(jsonPath("$.data.dishes[0].method.steps[0].instruction")
                        .value("翻炒"))
                .andExpect(jsonPath("$.data.dishes[0].ingredients[0].name").value("鸡蛋"));
    }

    private TodayMenuResponse today(String status, Long version, Long recordId) {
        return new TodayMenuResponse(
                31L, LocalDate.of(2026, 7, 11), status, version,
                0, 0, 0, List.of(), List.of(), null, null, null, null, recordId);
    }

    private TodayMenuResponse todayWithHouseholdDish() {
        return new TodayMenuResponse(
                31L, LocalDate.of(2026, 7, 11), "DRAFT", 4L,
                1, 0, 0, List.of(14L),
                List.of(new MenuDishResponse(
                        14L, "番茄炒蛋",
                        "https://www.osheeep.com/media/recipes/family.webp",
                        "家常菜", "酸甜", 10, "ME", "HOUSEHOLD", 8L,
                        new RecipeMethodSummaryResponse(21L, "家常做法", "炒"))),
                null, null, null, null, null);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
    ) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
