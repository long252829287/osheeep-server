package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerRecipePublishTransaction {

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeAuthorizer authorizer;
    private final DinnerRecipeQueryService queryService;
    private final DinnerImageAssetService imageAssetService;
    private final RecipeDraftValidator validator;

    public DinnerRecipePublishTransaction(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipeQueryService queryService,
            DinnerImageAssetService imageAssetService,
            RecipeDraftValidator validator
    ) {
        this.recipeMapper = recipeMapper;
        this.authorizer = authorizer;
        this.queryService = queryService;
        this.imageAssetService = imageAssetService;
        this.validator = validator;
    }

    @Transactional
    public RecipeDraftResponse publishChecked(Long userId, Long recipeId, long expectedVersion) {
        try {
            RecipeAccess membership = authorizer.requireMembershipForUpdate(userId);
            DinnerRecipeEntity draft = recipeMapper.selectByIdForUpdate(recipeId);
            if (draft == null) {
                throw new BusinessException(ErrorCode.DINNER_RECIPE_NOT_FOUND);
            }
            if (!Objects.equals(membership.householdId(), draft.getHouseholdId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            if (!"DRAFT".equals(draft.getStatus()) || !Objects.equals(userId, draft.getCreatorId())) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            if (!Objects.equals(draft.getVersion(), expectedVersion)) {
                throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
            }
            RecipeDraftResponse detail = queryService.detail(membership, recipeId);
            RecipePublishSnapshot snapshot = new RecipePublishSnapshot(
                    draft.getId(), draft.getCreatorId(), draft.getHouseholdId(), draft.getVersion(),
                    detail.name(), detail.category(), detail.flavor(), detail.servings(),
                    detail.estimatedMinutes(), draft.getImageAssetId(), detail.ingredients(),
                    detail.defaultMethod(), null);
            var issues = validator.validate(snapshot);
            if (!issues.isEmpty()) {
                throw new RecipeValidationException(issues);
            }
            imageAssetService.requireApproved(draft.getImageAssetId());
            draft.setStatus("PUBLISHED");
            draft.setPublishedAt(LocalDateTime.now());
            draft.setLastModifiedBy(userId);
            draft.setVersion(draft.getVersion() + 1L);
            recipeMapper.updateById(draft);
            return queryService.detail(membership, recipeId);
        } catch (DuplicateKeyException | PessimisticLockingFailureException exception) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
        }
    }
}
