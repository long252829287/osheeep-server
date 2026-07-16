package com.osheeep.server.dinner.recipe;

import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerRecipeDraftService {

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeAuthorizer authorizer;

    public DinnerRecipeDraftService(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeAuthorizer authorizer
    ) {
        this.recipeMapper = recipeMapper;
        this.authorizer = authorizer;
    }

    @Transactional
    public RecipeDraftResponse create(Long userId) {
        RecipeAccess access = authorizer.requireMembership(userId);
        DinnerRecipeEntity draft = new DinnerRecipeEntity();
        draft.setScope("HOUSEHOLD");
        draft.setHouseholdId(access.householdId());
        draft.setCreatorId(userId);
        draft.setLastModifiedBy(userId);
        draft.setStatus("DRAFT");
        draft.setVersion(1L);
        recipeMapper.insert(draft);
        return new RecipeDraftResponse(
                draft.getId(), draft.getStatus(), draft.getVersion(),
                draft.getName(), draft.getCategory(), draft.getFlavor(),
                draft.getServings(), draft.getEstimatedMinutes(),
                List.of(), null, null,
                List.of("BASIC", "INGREDIENTS", "METHOD", "IMAGE"), null);
    }
}
