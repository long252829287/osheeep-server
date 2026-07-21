package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.moderation.RecipeModerationTextBuilder;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipePublishSnapshotLoaderTest {
    @Mock private DinnerRecipeAuthorizer authorizer;
    @Mock private DinnerRecipeQueryService queryService;
    @Mock private DinnerImageAssetService imageAssetService;

    @Test
    void oldHouseholdDraftIsRejectedBeforeQueryTextOrImageLookup() {
        DinnerRecipePublishSnapshotLoader loader = loader();
        when(authorizer.requireMembership(7L)).thenReturn(new RecipeAccess(7L, 71L));
        when(authorizer.requireOwnedDraft(7L, 101L)).thenReturn(draft(70L, 4L));

        assertThatThrownBy(() -> loader.loadForModeration(7L, 101L, 4L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(queryService, never()).detail(7L, 101L);
        verify(imageAssetService, never()).requireApproved(9L);
    }

    @Test
    void createsStableModerationTextAfterAllPreconditionsPass() {
        DinnerRecipePublishSnapshotLoader loader = loader();
        when(authorizer.requireMembership(7L)).thenReturn(new RecipeAccess(7L, 70L));
        when(authorizer.requireOwnedDraft(7L, 101L)).thenReturn(draft(70L, 4L));
        when(queryService.detail(7L, 101L)).thenReturn(completeDetail());

        assertThat(loader.loadForModeration(7L, 101L, 4L).moderationText())
                .isEqualTo("口味：酸甜\n做法：家常炒\n烹饪方式：炒\n1. 切番茄");
        verify(imageAssetService).requireApproved(9L);
    }

    @Test
    void versionConflictStopsBeforeQuery() {
        DinnerRecipePublishSnapshotLoader loader = loader();
        when(authorizer.requireMembership(7L)).thenReturn(new RecipeAccess(7L, 70L));
        when(authorizer.requireOwnedDraft(7L, 101L)).thenReturn(draft(70L, 5L));
        assertThatThrownBy(() -> loader.loadForModeration(7L, 101L, 4L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));
        verify(queryService, never()).detail(7L, 101L);
    }

    @Test
    void incompleteDraftStopsBeforeImageAndModerationTextWork() {
        DinnerRecipePublishSnapshotLoader loader = loader();
        when(authorizer.requireMembership(7L)).thenReturn(new RecipeAccess(7L, 70L));
        when(authorizer.requireOwnedDraft(7L, 101L)).thenReturn(draft(70L, 4L));
        when(queryService.detail(7L, 101L)).thenReturn(incompleteDetail());

        assertThatThrownBy(() -> loader.loadForModeration(7L, 101L, 4L))
                .isInstanceOf(RecipeValidationException.class);

        verify(imageAssetService, never()).requireApproved(9L);
    }

    @Test
    void unapprovedImageStopsBeforeModerationTextWork() {
        RecipeModerationTextBuilder textBuilder = org.mockito.Mockito.mock(RecipeModerationTextBuilder.class);
        DinnerRecipePublishSnapshotLoader loader = loader(textBuilder);
        when(authorizer.requireMembership(7L)).thenReturn(new RecipeAccess(7L, 70L));
        when(authorizer.requireOwnedDraft(7L, 101L)).thenReturn(draft(70L, 4L));
        when(queryService.detail(7L, 101L)).thenReturn(completeDetail());
        when(imageAssetService.requireApproved(9L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_RECIPE_IMAGE_INVALID));

        assertThatThrownBy(() -> loader.loadForModeration(7L, 101L, 4L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_IMAGE_INVALID));

        verify(textBuilder, never()).build(any());
    }

    @Test
    void overlongMethodNameIsRejectedBeforeImageLookup() {
        DinnerRecipePublishSnapshotLoader loader = loader();
        when(authorizer.requireMembership(7L)).thenReturn(new RecipeAccess(7L, 70L));
        when(authorizer.requireOwnedDraft(7L, 101L)).thenReturn(draft(70L, 4L));
        when(queryService.detail(7L, 101L)).thenReturn(detailWithLongMethodName());

        assertThatThrownBy(() -> loader.loadForModeration(7L, 101L, 4L))
                .isInstanceOf(RecipeValidationException.class);

        verify(imageAssetService, never()).requireApproved(9L);
    }

    @Test
    void snapshotDefensivelyCopiesIngredientsAndMethodSteps() {
        List<RecipeIngredientResponse> ingredients = new ArrayList<>();
        List<RecipeMethodStepResponse> steps = new ArrayList<>();
        steps.add(new RecipeMethodStepResponse("切番茄", 0));
        RecipePublishSnapshot snapshot = new RecipePublishSnapshot(101L, 7L, 70L, 4L,
                "番茄炒蛋", "家常菜", "酸甜", 2, 15, 9L, ingredients,
                new RecipeMethodResponse(201L, "家常炒", "炒", steps), null);
        ingredients.add(new RecipeIngredientResponse(1L, "番茄", BigDecimal.ONE, "个", true, 0));
        steps.add(new RecipeMethodStepResponse("炒鸡蛋", 1));
        assertThat(snapshot.ingredients()).isEmpty();
        assertThat(snapshot.defaultMethod().steps()).hasSize(1);
        assertThatThrownBy(() -> snapshot.ingredients().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.defaultMethod().steps().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new RecipePublishSnapshot(101L, 7L, 70L, 4L,
                "番茄炒蛋", "家常菜", "酸甜", 2, 15, 9L, null, null, null)
                .ingredients()).isNull();
    }

    private DinnerRecipePublishSnapshotLoader loader() {
        return loader(new RecipeModerationTextBuilder());
    }

    private DinnerRecipePublishSnapshotLoader loader(RecipeModerationTextBuilder textBuilder) {
        return new DinnerRecipePublishSnapshotLoader(authorizer, queryService, new RecipeDraftValidator(),
                textBuilder, imageAssetService);
    }

    private DinnerRecipeEntity draft(long householdId, long version) {
        DinnerRecipeEntity draft = new DinnerRecipeEntity();
        draft.setId(101L); draft.setCreatorId(7L); draft.setHouseholdId(householdId);
        draft.setVersion(version); draft.setImageAssetId(9L); draft.setStatus("DRAFT");
        return draft;
    }

    private RecipeDraftResponse completeDetail() {
        return new RecipeDraftResponse(101L, "DRAFT", 4L, "番茄炒蛋", "家常菜", "酸甜", 2, 15,
                List.of(new RecipeIngredientResponse(1L, "番茄", BigDecimal.ONE, "个", true, 0)),
                new RecipeMethodResponse(201L, "家常炒", "炒",
                        List.of(new RecipeMethodStepResponse("切番茄", 0))),
                null, List.of(), null);
    }

    private RecipeDraftResponse incompleteDetail() {
        return new RecipeDraftResponse(101L, "DRAFT", 4L, null, null, null, null, null,
                List.of(), null, null, List.of("BASIC", "INGREDIENTS", "METHOD", "IMAGE"), null);
    }

    private RecipeDraftResponse detailWithLongMethodName() {
        return new RecipeDraftResponse(101L, "DRAFT", 4L, "番茄炒蛋", "家常菜", "酸甜", 2, 15,
                List.of(new RecipeIngredientResponse(1L, "番茄", BigDecimal.ONE, "个", true, 0)),
                new RecipeMethodResponse(201L, "做法" + "x".repeat(39), "炒",
                        List.of(new RecipeMethodStepResponse("切番茄", 0))),
                null, List.of(), null);
    }
}
