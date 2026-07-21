package com.osheeep.server.dinner.recipe;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMatchResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodSummaryResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestUserMapperConfig.class)
class DinnerRecipeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private DinnerRecipeService recipeService;

    private String token;

    @BeforeEach
    void setUp() {
        reset(recipeService);
        token = jwtService.generateToken(new CurrentUser(7L, "wx_user"));
    }

    @Test
    void recipeDiscoveryRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/dinner/recipes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void defaultsTemporarySetsToEmptyAndPreservesExpandedRecipeContract() throws Exception {
        when(recipeService.discover(7L, Set.of(), Set.of(), false))
                .thenReturn(List.of(response()));

        mockMvc.perform(authenticated(get("/api/dinner/recipes")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("番茄炒蛋"))
                .andExpect(jsonPath("$.data[0].imagePath").value("/assets/recipes/tomato-eggs.jpg"))
                .andExpect(jsonPath("$.data[0].category").value("家常菜"))
                .andExpect(jsonPath("$.data[0].flavor").value("酸甜"))
                .andExpect(jsonPath("$.data[0].estimatedMinutes").value(10))
                .andExpect(jsonPath("$.data[0].scope").value("HOUSEHOLD"))
                .andExpect(jsonPath("$.data[0].version").value(8))
                .andExpect(jsonPath("$.data[0].defaultMethod.id").value(21))
                .andExpect(jsonPath("$.data[0].defaultMethod.name").value("家常做法"))
                .andExpect(jsonPath("$.data[0].defaultMethod.cookingStyle").value("炒"))
                .andExpect(jsonPath("$.data[0].defaultMethod.steps").doesNotExist())
                .andExpect(jsonPath("$.data[0].ingredients[0].ingredientId").value(101))
                .andExpect(jsonPath("$.data[0].ingredients[0].name").value("番茄"))
                .andExpect(jsonPath("$.data[0].ingredients[0].quantity").value(2.000))
                .andExpect(jsonPath("$.data[0].ingredients[0].unit").value("个"))
                .andExpect(jsonPath("$.data[0].ingredients[0].required").value(true))
                .andExpect(jsonPath("$.data[0].ingredients[0].sortOrder").value(1))
                .andExpect(jsonPath("$.data[0].match.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data[0].match.matchPercent").value(100));
        verify(recipeService).discover(7L, Set.of(), Set.of(), false);
    }

    @Test
    void passesTemporaryIngredientSetsAndOnlyCookableToDiscovery() throws Exception {
        when(recipeService.discover(7L, Set.of(101L, 102L), Set.of(103L), true))
                .thenReturn(List.of());

        mockMvc.perform(authenticated(get("/api/dinner/recipes")
                        .queryParam("includeIngredientIds", "101", "102")
                        .queryParam("excludeIngredientIds", "103")
                        .queryParam("onlyCookable", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        verify(recipeService).discover(7L, Set.of(101L, 102L), Set.of(103L), true);
    }

    private RecipeResponse response() {
        return new RecipeResponse(
                1L,
                "番茄炒蛋",
                "/assets/recipes/tomato-eggs.jpg",
                "家常菜",
                "酸甜",
                10,
                "HOUSEHOLD",
                8L,
                new RecipeMethodSummaryResponse(21L, "家常做法", "炒"),
                List.of(new RecipeIngredientResponse(
                        101L, "番茄", new BigDecimal("2.000"), "个", true, 1)),
                new RecipeMatchResponse("AVAILABLE", 1, 1, 100, List.of(), List.of()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
    ) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
