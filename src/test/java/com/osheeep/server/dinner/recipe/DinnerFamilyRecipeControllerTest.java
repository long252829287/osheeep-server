package com.osheeep.server.dinner.recipe;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeListItemResponse;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeTab;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientInput;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepInput;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
import com.osheeep.server.dinner.recipe.dto.ReplaceRecipeIngredientsRequest;
import com.osheeep.server.dinner.recipe.dto.SelectRecipeImageRequest;
import com.osheeep.server.dinner.recipe.dto.UpdateDefaultMethodRequest;
import com.osheeep.server.dinner.recipe.dto.UpdateRecipeBasicInfoRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class DinnerFamilyRecipeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private DinnerRecipeDraftService draftService;
    @MockitoBean private DinnerRecipeQueryService queryService;
    @MockitoBean private DinnerRecipePublicationService publicationService;

    private String token;

    @BeforeEach
    void setUp() {
        reset(draftService, queryService, publicationService);
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
        mockMvc.perform(put("/api/dinner/recipes/101/basic-info")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        mockMvc.perform(put("/api/dinner/recipes/101/ingredients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"ingredients\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        mockMvc.perform(put("/api/dinner/recipes/101/default-method")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"steps\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        mockMvc.perform(put("/api/dinner/recipes/101/image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"imageAssetId\":9}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/dinner/recipes/101/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
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
                        101L, "PUBLISHED", "番茄炒蛋",
                        "https://assets.test/media/recipes/tomato.webp",
                        "家常菜", "咸鲜", 2, 15, 4L, 7L, "小羊",
                        8L, "伙伴", "PREVIEW", Instant.parse("2026-07-16T12:30:00Z"))));

        mockMvc.perform(authenticated(get("/api/dinner/recipes/family")
                        .queryParam("tab", "PUBLISHED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(101))
                .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data[0].name").value("番茄炒蛋"))
                .andExpect(jsonPath("$.data[0].imageUrl")
                        .value("https://assets.test/media/recipes/tomato.webp"))
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
                        .value("https://assets.test/media/recipes/tomato-with-egg-list.webp"))
                .andExpect(jsonPath("$.data.image.detailUrl")
                        .value("https://assets.test/media/recipes/tomato-with-egg-detail.webp"))
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

    @Test
    void basicInfoRouteAllowsIncompleteDraftAndReturnsLatestVersion() throws Exception {
        UpdateRecipeBasicInfoRequest request = new UpdateRecipeBasicInfoRequest(
                3L, null, null, null, null, null);
        when(draftService.updateBasicInfo(7L, 101L, request)).thenReturn(blankDraft(4L));

        mockMvc.perform(authenticated(put("/api/dinner/recipes/101/basic-info"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3,\"name\":null,\"category\":null,"
                                + "\"flavor\":null,\"servings\":null,"
                                + "\"estimatedMinutes\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.version").value(4));
        verify(draftService).updateBasicInfo(7L, 101L, request);
    }

    @Test
    void ingredientRoutePassesValidatedReplacementAndReturnsLatestVersion() throws Exception {
        ReplaceRecipeIngredientsRequest request = new ReplaceRecipeIngredientsRequest(
                4L, List.of(new RecipeIngredientInput(1L, null, "克", true)));
        when(draftService.replaceIngredients(7L, 101L, request)).thenReturn(blankDraft(5L));

        mockMvc.perform(authenticated(put("/api/dinner/recipes/101/ingredients"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":4,\"ingredients\":[{\"ingredientId\":1,"
                                + "\"quantity\":null,\"unit\":\"克\",\"required\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(5));
        verify(draftService).replaceIngredients(7L, 101L, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"ingredientId\":1,\"unit\":\"克\"}",
            "{\"ingredientId\":1,\"unit\":\"克\",\"required\":null}"
    })
    void ingredientRouteRequiresExplicitRequiredState(String ingredientJson) throws Exception {
        mockMvc.perform(authenticated(put("/api/dinner/recipes/101/ingredients"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"ingredients\":[" + ingredientJson + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(draftService);
    }

    @Test
    void defaultMethodRoutePassesOrderedStepsAndReturnsLatestVersion() throws Exception {
        UpdateDefaultMethodRequest request = new UpdateDefaultMethodRequest(
                5L, "家常做法", "炒",
                List.of(new RecipeMethodStepInput("热锅"), new RecipeMethodStepInput("")));
        when(draftService.updateDefaultMethod(7L, 101L, request)).thenReturn(blankDraft(6L));

        mockMvc.perform(authenticated(put("/api/dinner/recipes/101/default-method"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":5,\"name\":\"家常做法\","
                                + "\"cookingStyle\":\"炒\",\"steps\":["
                                + "{\"instruction\":\"热锅\"},{\"instruction\":\"\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(6));
        verify(draftService).updateDefaultMethod(7L, 101L, request);
    }

    @Test
    void imageRoutePassesVersionedSelectionAndAllowsExplicitClear() throws Exception {
        SelectRecipeImageRequest select = new SelectRecipeImageRequest(6L, 9L);
        when(draftService.selectImage(7L, 101L, select)).thenReturn(blankDraft(7L));

        mockMvc.perform(authenticated(put("/api/dinner/recipes/101/image"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":6,\"imageAssetId\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(7));
        verify(draftService).selectImage(7L, 101L, select);

        SelectRecipeImageRequest clear = new SelectRecipeImageRequest(7L, null);
        when(draftService.selectImage(7L, 101L, clear)).thenReturn(blankDraft(8L));
        mockMvc.perform(authenticated(put("/api/dinner/recipes/101/image"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":7,\"imageAssetId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(8));
        verify(draftService).selectImage(7L, 101L, clear);
    }

    @Test
    void imageRouteRejectsNonPositiveVersions() throws Exception {
        mockMvc.perform(authenticated(put("/api/dinner/recipes/101/image"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"imageAssetId\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(draftService);
    }

    @Test
    void publishRouteMapsPublicationResultsAndErrors() throws Exception {
        when(publicationService.publish(7L, 101L, 4L)).thenReturn(
                new RecipeDraftResponse(101L, "PUBLISHED", 5L, "番茄炒蛋", "家常菜", "酸甜",
                        2, 15, List.of(), null, null, List.of(), null));
        mockMvc.perform(authenticated(post("/api/dinner/recipes/101/publish"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        verify(publicationService).publish(7L, 101L, 4L);

        when(publicationService.publish(7L, 101L, 5L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_RECIPE_CONTENT_REJECTED));
        mockMvc.perform(authenticated(post("/api/dinner/recipes/101/publish"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":5}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("DINNER_RECIPE_CONTENT_REJECTED"));

        when(publicationService.publish(7L, 101L, 6L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_RECIPE_MODERATION_UNAVAILABLE));
        mockMvc.perform(authenticated(post("/api/dinner/recipes/101/publish"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":6}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DINNER_RECIPE_MODERATION_UNAVAILABLE"));

        when(publicationService.publish(7L, 101L, 7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));
        mockMvc.perform(authenticated(post("/api/dinner/recipes/101/publish"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DINNER_RECIPE_VERSION_CONFLICT"));

        mockMvc.perform(authenticated(post("/api/dinner/recipes/101/publish"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void unavailableImageMapsToTheExactUnprocessableEntityError() throws Exception {
        SelectRecipeImageRequest request = new SelectRecipeImageRequest(6L, 8L);
        when(draftService.selectImage(7L, 101L, request))
                .thenThrow(new BusinessException(ErrorCode.DINNER_RECIPE_IMAGE_INVALID));

        mockMvc.perform(authenticated(put("/api/dinner/recipes/101/image"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":6,\"imageAssetId\":8}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode")
                        .value("DINNER_RECIPE_IMAGE_INVALID"))
                .andExpect(jsonPath("$.message")
                        .value("Dinner recipe image is unavailable"));
    }

    @Test
    void writeRoutesEnforceSafeMaximumsAndRequiredNestedFields() throws Exception {
        mockMvc.perform(authenticated(put("/api/dinner/recipes/101/basic-info"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"name\":\""
                                + "菜".repeat(41) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        mockMvc.perform(authenticated(put("/api/dinner/recipes/101/ingredients"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"ingredients\":[{"
                                + "\"ingredientId\":1,\"unit\":\" \","
                                + "\"required\":true}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        String steps = java.util.stream.IntStream.range(0, 13)
                .mapToObj(index -> "{\"instruction\":\"步骤" + index + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(authenticated(put("/api/dinner/recipes/101/default-method"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"steps\":[" + steps + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    private RecipeDraftResponse blankDraft() {
        return blankDraft(1L);
    }

    private RecipeDraftResponse blankDraft(Long version) {
        return new RecipeDraftResponse(
                101L, "DRAFT", version, null, null, null, null, null,
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
                        "https://assets.test/media/recipes/tomato-with-egg-list.webp",
                        "https://assets.test/media/recipes/tomato-with-egg-detail.webp",
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
