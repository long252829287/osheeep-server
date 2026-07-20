package com.osheeep.server.dinner.recipe;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DinnerRecipeAuthorizer {

    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerHouseholdMapper householdMapper;
    private final DinnerRecipeMapper recipeMapper;

    public DinnerRecipeAuthorizer(
            DinnerHouseholdMemberMapper memberMapper,
            DinnerHouseholdMapper householdMapper,
            DinnerRecipeMapper recipeMapper
    ) {
        this.memberMapper = memberMapper;
        this.householdMapper = householdMapper;
        this.recipeMapper = recipeMapper;
    }

    public RecipeAccess requireMembership(Long userId) {
        DinnerHouseholdMemberEntity membership = memberMapper.selectOne(
                Wrappers.<DinnerHouseholdMemberEntity>lambdaQuery()
                        .eq(DinnerHouseholdMemberEntity::getUserId, userId)
                        .last("LIMIT 1"));
        if (membership == null) {
            throw forbidden();
        }
        DinnerHouseholdEntity household = householdMapper.selectById(membership.getHouseholdId());
        if (household == null || !"ACTIVE".equals(household.getStatus())) {
            throw forbidden();
        }
        return new RecipeAccess(userId, membership.getHouseholdId());
    }

    public RecipeAccess requireMembershipForUpdate(Long userId) {
        DinnerHouseholdMemberEntity membership = memberMapper.selectByUserIdForUpdate(userId);
        if (membership == null) {
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
