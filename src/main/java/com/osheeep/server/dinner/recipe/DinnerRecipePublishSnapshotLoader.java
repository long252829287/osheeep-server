package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.moderation.RecipeModerationTextBuilder;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DinnerRecipePublishSnapshotLoader {

    private final DinnerRecipeAuthorizer authorizer;
    private final DinnerRecipeQueryService queryService;
    private final RecipeDraftValidator validator;
    private final RecipeModerationTextBuilder textBuilder;
    private final DinnerImageAssetService imageAssetService;

    public DinnerRecipePublishSnapshotLoader(
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipeQueryService queryService,
            RecipeDraftValidator validator,
            RecipeModerationTextBuilder textBuilder,
            DinnerImageAssetService imageAssetService
    ) {
        this.authorizer = authorizer;
        this.queryService = queryService;
        this.validator = validator;
        this.textBuilder = textBuilder;
        this.imageAssetService = imageAssetService;
    }

    public RecipePublishSnapshot loadForModeration(Long userId, Long recipeId, long expectedVersion) {
        RecipeAccess access = authorizer.requireMembership(userId);
        var recipe = authorizer.requireOwnedDraft(access, recipeId);
        if (!Objects.equals(recipe.getVersion(), expectedVersion)) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
        }
        RecipeDraftResponse detail = queryService.detail(access, recipeId);
        RecipePublishSnapshot snapshot = new RecipePublishSnapshot(
                recipe.getId(), recipe.getCreatorId(), recipe.getHouseholdId(), recipe.getVersion(),
                detail.name(), detail.category(), detail.flavor(), detail.servings(),
                detail.estimatedMinutes(), recipe.getImageAssetId(), detail.ingredients(),
                detail.defaultMethod(), null);
        var issues = validator.validate(snapshot);
        if (!issues.isEmpty()) {
            throw new RecipeValidationException(issues);
        }
        imageAssetService.requireApproved(snapshot.imageAssetId());
        return new RecipePublishSnapshot(
                snapshot.recipeId(), snapshot.creatorId(), snapshot.householdId(), snapshot.version(),
                snapshot.name(), snapshot.category(), snapshot.flavor(), snapshot.servings(),
                snapshot.estimatedMinutes(), snapshot.imageAssetId(), snapshot.ingredients(),
                snapshot.defaultMethod(), textBuilder.build(snapshot));
    }
}
