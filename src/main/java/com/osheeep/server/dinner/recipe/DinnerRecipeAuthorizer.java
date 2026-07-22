package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
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
    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerHouseholdMapper householdMapper;
    private final DinnerRecipeMapper recipeMapper;

    @Autowired
    public DinnerRecipeAuthorizer(
            DinnerHouseholdAccessService accessService,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerHouseholdMapper householdMapper,
            DinnerRecipeMapper recipeMapper
    ) {
        this.accessService = accessService;
        this.memberMapper = memberMapper;
        this.householdMapper = householdMapper;
        this.recipeMapper = recipeMapper;
    }

    DinnerRecipeAuthorizer(
            DinnerHouseholdMemberMapper memberMapper,
            DinnerHouseholdMapper householdMapper,
            DinnerRecipeMapper recipeMapper
    ) {
        this(new DinnerHouseholdAccessService(memberMapper, householdMapper),
                memberMapper, householdMapper, recipeMapper);
    }

    public RecipeAccess requireMembership(Long userId) {
        ActiveHouseholdAccess access = accessService.requireActiveHousehold(userId);
        return new RecipeAccess(access.userId(), access.householdId());
    }

    public RecipeAccess requireMembershipForUpdate(Long userId) {
        DinnerHouseholdMemberEntity membership = memberMapper.selectByUserIdForUpdate(userId);
        if (membership == null || !"ACTIVE".equals(membership.getStatus())) {
            throw forbidden();
        }
        DinnerHouseholdEntity household = householdMapper.selectByIdForUpdate(membership.getHouseholdId());
        if (household == null || !"ACTIVE".equals(household.getStatus())) {
            throw forbidden();
        }
        return new RecipeAccess(userId, membership.getHouseholdId());
    }

    public DinnerRecipeEntity requireOwnedDraft(Long userId, Long recipeId) {
        RecipeAccess access = requireMembership(userId);
        DinnerRecipeEntity recipe = recipeMapper.selectById(recipeId);
        if (recipe == null
                || !"DRAFT".equals(recipe.getStatus())
                || !Objects.equals(userId, recipe.getCreatorId())
                || !Objects.equals(access.householdId(), recipe.getHouseholdId())) {
            throw forbidden();
        }
        return recipe;
    }

    public DinnerRecipeEntity requireVisible(Long userId, Long recipeId) {
        RecipeAccess access = requireMembership(userId);
        DinnerRecipeEntity recipe = recipeMapper.selectById(recipeId);
        if (recipe == null) {
            throw forbidden();
        }
        boolean visible = switch (recipe.getStatus()) {
            case "DRAFT" -> Objects.equals(userId, recipe.getCreatorId())
                    && Objects.equals(access.householdId(), recipe.getHouseholdId());
            case "PUBLISHED", "ARCHIVED" ->
                    Objects.equals(access.householdId(), recipe.getHouseholdId());
            default -> false;
        };
        if (!visible) {
            throw forbidden();
        }
        return recipe;
    }

    private BusinessException forbidden() {
        return new BusinessException(ErrorCode.FORBIDDEN);
    }

    public record RecipeAccess(Long userId, Long householdId) {
    }
}
