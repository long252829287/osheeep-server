package com.osheeep.server.dinner.recipe;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeListItemResponse;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeTab;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
class DinnerFamilyRecipeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private DinnerRecipeDraftService draftService;
    @MockitoBean private DinnerRecipeQueryService queryService;

    private String token;

    @BeforeEach
    void setUp() {
        reset(draftService, queryService);
        token = jwtService.generateToken(new CurrentUser(7L, "wx_user"));
    }

    @Test
    void customRecipeRoutesRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/dinner/recipes/drafts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/dinner/recipes/family").queryParam("tab", "PUBLISHED"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/dinner/recipes/101"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void createDraftReturnsTheExactInitialContract() throws Exception {
        when(draftService.create(7L)).thenReturn(blankDraft());

        mockMvc.perform(authenticated(post("/api/dinner/recipes/drafts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.ingredients").isArray())
                .andExpect(jsonPath("$.data.ingredients").isEmpty())
                .andExpect(jsonPath("$.data.defaultMethod").doesNotExist())
                .andExpect(jsonPath("$.data.image").doesNotExist())
                .andExpect(jsonPath("$.data.incompleteSteps[0]").value("BASIC"))
                .andExpect(jsonPath("$.data.incompleteSteps[3]").value("IMAGE"));
        verify(draftService).create(7L);
    }

    @Test
    void familyListRoutesTheTabAndReturnsTheExactListContract() throws Exception {
        when(queryService.list(7L, FamilyRecipeTab.PUBLISHED)).thenReturn(List.of(
                new FamilyRecipeListItemResponse(
                        101L, "PUBLISHED", "番茄炒蛋", "/media/recipes/tomato.webp",
                        "家常菜", "咸鲜", 2, 15, 4L, 7L, "小羊",
                        8L, "伙伴", "PREVIEW", Instant.parse("2026-07-16T12:30:00Z"))));

        mockMvc.perform(authenticated(get("/api/dinner/recipes/family")
                        .queryParam("tab", "PUBLISHED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(101))
                .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data[0].name").value("番茄炒蛋"))
                .andExpect(jsonPath("$.data[0].imageUrl").value("/media/recipes/tomato.webp"))
                .andExpect(jsonPath("$.data[0].category").value("家常菜"))
                .andExpect(jsonPath("$.data[0].flavor").value("咸鲜"))
                .andExpect(jsonPath("$.data[0].servings").value(2))
                .andExpect(jsonPath("$.data[0].estimatedMinutes").value(15))
                .andExpect(jsonPath("$.data[0].version").value(4))
                .andExpect(jsonPath("$.data[0].creatorId").value(7))
                .andExpect(jsonPath("$.data[0].creatorName").value("小羊"))
                .andExpect(jsonPath("$.data[0].lastModifiedBy").value(8))
                .andExpect(jsonPath("$.data[0].lastModifiedByName").value("伙伴"))
                .andExpect(jsonPath("$.data[0].completedStep").value("PREVIEW"))
                .andExpect(jsonPath("$.data[0].updatedAt").value("2026-07-16T12:30:00Z"));
        verify(queryService).list(7L, FamilyRecipeTab.PUBLISHED);
    }

    @Test
    void detailReturnsTheExactAggregateContract() throws Exception {
        when(queryService.detail(7L, 101L)).thenReturn(completeDraft());

        mockMvc.perform(authenticated(get("/api/dinner/recipes/101")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.name").value("番茄炒蛋"))
                .andExpect(jsonPath("$.data.category").value("家常菜"))
                .andExpect(jsonPath("$.data.flavor").value("咸鲜"))
                .andExpect(jsonPath("$.data.servings").value(2))
                .andExpect(jsonPath("$.data.estimatedMinutes").value(15))
                .andExpect(jsonPath("$.data.ingredients[0].ingredientId").value(1))
                .andExpect(jsonPath("$.data.defaultMethod.id").value(201))
                .andExpect(jsonPath("$.data.defaultMethod.name").value("家常做法"))
                .andExpect(jsonPath("$.data.defaultMethod.cookingStyle").value("炒"))
                .andExpect(jsonPath("$.data.defaultMethod.steps[0].instruction").value("炒熟"))
                .andExpect(jsonPath("$.data.defaultMethod.steps[0].sortOrder").value(1))
                .andExpect(jsonPath("$.data.image.id").value(9))
                .andExpect(jsonPath("$.data.image.listUrl")
                        .value("/media/recipes/tomato-with-egg-list.webp"))
                .andExpect(jsonPath("$.data.image.detailUrl")
                        .value("/media/recipes/tomato-with-egg-detail.webp"))
                .andExpect(jsonPath("$.data.image.sourcePageUrl")
                        .value("https://commons.wikimedia.org/wiki/File:Tomato_with_egg.jpg"))
                .andExpect(jsonPath("$.data.image.author").value("Kaap bij Sneeuw"))
                .andExpect(jsonPath("$.data.image.licenseName").value("CC0 1.0"))
                .andExpect(jsonPath("$.data.image.licenseUrl")
                        .value("https://creativecommons.org/publicdomain/zero/1.0/"))
                .andExpect(jsonPath("$.data.image.acquiredOn").value("2026-07-16"))
                .andExpect(jsonPath("$.data.image.width").value(1198))
                .andExpect(jsonPath("$.data.image.height").value(1091))
                .andExpect(jsonPath("$.data.incompleteSteps").isEmpty())
                .andExpect(jsonPath("$.data.updatedAt").value("2026-07-16T12:30:00Z"));
        verify(queryService).detail(7L, 101L);
    }

    private RecipeDraftResponse blankDraft() {
        return new RecipeDraftResponse(
                101L, "DRAFT", 1L, null, null, null, null, null,
                List.of(), null, null,
                List.of("BASIC", "INGREDIENTS", "METHOD", "IMAGE"), null);
    }

    private RecipeDraftResponse completeDraft() {
        return new RecipeDraftResponse(
                101L, "DRAFT", 4L, "番茄炒蛋", "家常菜", "咸鲜", 2, 15,
                List.of(new RecipeIngredientResponse(
                        1L, "番茄", BigDecimal.ONE, "个", true, 1)),
                new RecipeMethodResponse(
                        201L, "家常做法", "炒",
                        List.of(new RecipeMethodStepResponse("炒熟", 1))),
                new ImageAssetResponse(
                        9L, "番茄炒鸡蛋",
                        "/media/recipes/tomato-with-egg-list.webp",
                        "/media/recipes/tomato-with-egg-detail.webp",
                        "https://commons.wikimedia.org/wiki/File:Tomato_with_egg.jpg",
                        "Kaap bij Sneeuw", "CC0 1.0",
                        "https://creativecommons.org/publicdomain/zero/1.0/",
                        LocalDate.of(2026, 7, 16), 1198, 1091),
                List.of(), Instant.parse("2026-07-16T12:30:00Z"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
    ) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
