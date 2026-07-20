package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipePublishTransactionTest {

    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipeAuthorizer authorizer;
    @Mock private DinnerRecipeQueryService queryService;
    @Mock private DinnerImageAssetService imageAssetService;

    @Test
    void checkedVersionIsLockedRevalidatedAndPublished() {
        DinnerRecipePublishTransaction transaction = transaction();
        DinnerRecipeEntity locked = completeDraft(4L);
        when(authorizer.requireMembership(7L)).thenReturn(new RecipeAccess(7L, 70L));
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(locked);
        when(queryService.detail(7L, 101L)).thenReturn(completeResponse(), publishedResponse());

        assertThat(transaction.publishChecked(7L, 101L, 4L).status()).isEqualTo("PUBLISHED");

        ArgumentCaptor<DinnerRecipeEntity> row = ArgumentCaptor.forClass(DinnerRecipeEntity.class);
        verify(recipeMapper).updateById(row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo("PUBLISHED");
        assertThat(row.getValue().getVersion()).isEqualTo(5L);
        assertThat(row.getValue().getPublishedAt()).isNotNull();
        verify(imageAssetService).requireApproved(9L);
    }

    @Test
    void versionChangedDuringModerationPreservesDraftAndReturnsConflict() {
        DinnerRecipePublishTransaction transaction = transaction();
        when(authorizer.requireMembership(7L)).thenReturn(new RecipeAccess(7L, 70L));
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(completeDraft(5L));

        assertThatThrownBy(() -> transaction.publishChecked(7L, 101L, 4L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));
        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
    }

    private DinnerRecipePublishTransaction transaction() {
        return new DinnerRecipePublishTransaction(recipeMapper, authorizer, queryService,
                imageAssetService, new RecipeDraftValidator());
    }

    private DinnerRecipeEntity completeDraft(long version) {
        DinnerRecipeEntity draft = new DinnerRecipeEntity();
        draft.setId(101L); draft.setCreatorId(7L); draft.setHouseholdId(70L); draft.setVersion(version);
        draft.setStatus("DRAFT"); draft.setImageAssetId(9L);
        return draft;
    }

    private RecipeDraftResponse completeResponse() {
        return new RecipeDraftResponse(101L, "DRAFT", 4L, "番茄炒蛋", "家常菜", "酸甜", 2, 15,
                List.of(new RecipeIngredientResponse(1L, "番茄", BigDecimal.ONE, "个", true, 0)),
                new RecipeMethodResponse(201L, null, null,
                        List.of(new RecipeMethodStepResponse("切番茄", 0))),
                null, List.of(), null);
    }

    private RecipeDraftResponse publishedResponse() {
        RecipeDraftResponse draft = completeResponse();
        return new RecipeDraftResponse(draft.id(), "PUBLISHED", 5L, draft.name(), draft.category(),
                draft.flavor(), draft.servings(), draft.estimatedMinutes(), draft.ingredients(),
                draft.defaultMethod(), draft.image(), draft.incompleteSteps(), draft.updatedAt());
    }
}
