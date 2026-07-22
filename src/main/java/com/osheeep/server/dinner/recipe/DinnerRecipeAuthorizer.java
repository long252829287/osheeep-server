package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.LockedHouseholdContext;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DinnerRecipeAuthorizer {

    private final DinnerHouseholdAccessService accessService;
    private final DinnerRecipeMapper recipeMapper;

    @Autowired
    public DinnerRecipeAuthorizer(
            DinnerHouseholdAccessService accessService,
            DinnerRecipeMapper recipeMapper
    ) {
        this.accessService = accessService;
        this.recipeMapper = recipeMapper;
    }

    DinnerRecipeAuthorizer(
            DinnerHouseholdMemberMapper memberMapper,
            DinnerHouseholdMapper householdMapper,
            DinnerRecipeMapper recipeMapper
    ) {
        this(new DinnerHouseholdAccessService(memberMapper, householdMapper), recipeMapper);
    }

    RecipeAccess requireMembership(Long userId) {
        ActiveHouseholdAccess access = accessService.requireActiveHousehold(userId);
        return new RecipeAccess(access.userId(), access.householdId());
    }

    RecipeAccess requireMembershipForUpdate(Long userId) {
        LockedHouseholdContext context = accessService.lockActiveHouseholdContext(userId);
        ActiveHouseholdAccess access = context.access();
        return new RecipeAccess(access.userId(), access.householdId());
    }

    public DinnerRecipeEntity requireOwnedDraft(Long userId, Long recipeId) {
        return requireOwnedDraft(requireMembership(userId), recipeId);
    }

    DinnerRecipeEntity requireOwnedDraft(RecipeAccess access, Long recipeId) {
        RecipeAccess validatedAccess = requireAccess(access);
        DinnerRecipeEntity recipe = recipeMapper.selectById(recipeId);
        if (recipe == null
                || !"DRAFT".equals(recipe.getStatus())
                || !Objects.equals(validatedAccess.userId(), recipe.getCreatorId())
                || !Objects.equals(validatedAccess.householdId(), recipe.getHouseholdId())) {
            throw forbidden();
        }
        return recipe;
    }

    public DinnerRecipeEntity requireVisible(Long userId, Long recipeId) {
        return requireVisible(requireMembership(userId), recipeId);
    }

    DinnerRecipeEntity requireVisible(RecipeAccess access, Long recipeId) {
        RecipeAccess validatedAccess = requireAccess(access);
        DinnerRecipeEntity recipe = recipeMapper.selectById(recipeId);
        if (recipe == null) {
            throw forbidden();
        }
        boolean visible = switch (recipe.getStatus()) {
            case "DRAFT" -> Objects.equals(validatedAccess.userId(), recipe.getCreatorId())
                    && Objects.equals(validatedAccess.householdId(), recipe.getHouseholdId());
            case "PUBLISHED", "ARCHIVED" ->
                    Objects.equals(validatedAccess.householdId(), recipe.getHouseholdId());
            default -> false;
        };
        if (!visible) {
            throw forbidden();
        }
        return recipe;
    }

    private RecipeAccess requireAccess(RecipeAccess access) {
        if (access == null || access.userId() == null || access.householdId() == null) {
            throw forbidden();
        }
        return access;
    }

    private BusinessException forbidden() {
        return new BusinessException(ErrorCode.FORBIDDEN);
    }

    record RecipeAccess(Long userId, Long householdId) {
    }
}
