package com.osheeep.server.dinner.household;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import com.osheeep.server.dinner.menu.entity.DinnerMenuActionEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuActionMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeIngredientEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import com.osheeep.server.dinner.record.entity.DinnerCookingRecordEntity;
import com.osheeep.server.dinner.record.entity.DinnerRecordDishSnapshotEntity;
import com.osheeep.server.dinner.record.mapper.DinnerCookingRecordMapper;
import com.osheeep.server.dinner.record.mapper.DinnerRecordDishSnapshotMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DinnerAccountCleanupService {
    private final DinnerHouseholdMapper householdMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final DinnerInviteCodeMapper inviteMapper;
    private final DinnerMenuMapper menuMapper;
    private final DinnerMenuSelectionMapper selectionMapper;
    private final DinnerMenuActionMapper actionMapper;
    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeIngredientMapper recipeIngredientMapper;
    private final DinnerRecipeMethodMapper recipeMethodMapper;
    private final DinnerRecipeMethodStepMapper recipeMethodStepMapper;
    private final DinnerHouseholdInventoryMapper inventoryMapper;
    private final DinnerIngredientMapper ingredientMapper;
    private final DinnerCookingRecordMapper recordMapper;
    private final DinnerRecordDishSnapshotMapper snapshotMapper;

    public DinnerAccountCleanupService(
            DinnerHouseholdMapper householdMapper,
            DinnerHouseholdMemberMapper memberMapper,
            DinnerInviteCodeMapper inviteMapper,
            DinnerMenuMapper menuMapper,
            DinnerMenuSelectionMapper selectionMapper,
            DinnerMenuActionMapper actionMapper,
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeIngredientMapper recipeIngredientMapper,
            DinnerRecipeMethodMapper recipeMethodMapper,
            DinnerRecipeMethodStepMapper recipeMethodStepMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerIngredientMapper ingredientMapper,
            DinnerCookingRecordMapper recordMapper,
            DinnerRecordDishSnapshotMapper snapshotMapper
    ) {
        this.householdMapper = householdMapper;
        this.memberMapper = memberMapper;
        this.inviteMapper = inviteMapper;
        this.menuMapper = menuMapper;
        this.selectionMapper = selectionMapper;
        this.actionMapper = actionMapper;
        this.recipeMapper = recipeMapper;
        this.recipeIngredientMapper = recipeIngredientMapper;
        this.recipeMethodMapper = recipeMethodMapper;
        this.recipeMethodStepMapper = recipeMethodStepMapper;
        this.inventoryMapper = inventoryMapper;
        this.ingredientMapper = ingredientMapper;
        this.recordMapper = recordMapper;
        this.snapshotMapper = snapshotMapper;
    }

    public void removeUser(Long userId, LocalDateTime deletedAt) {
        DinnerHouseholdMemberEntity membership = memberMapper.selectByUserIdForUpdate(userId);
        if (membership == null) {
            return;
        }
        Long householdId = membership.getHouseholdId();
        DinnerHouseholdEntity household = householdMapper.selectByIdForUpdate(householdId);
        if (household == null) {
            deleteMembership(householdId, userId);
            return;
        }
        long memberCount = memberMapper.selectCount(
                Wrappers.<DinnerHouseholdMemberEntity>lambdaQuery()
                        .eq(DinnerHouseholdMemberEntity::getHouseholdId, householdId));
        if (memberCount > 1) {
            inviteMapper.update(null,
                    Wrappers.<DinnerInviteCodeEntity>lambdaUpdate()
                            .eq(DinnerInviteCodeEntity::getHouseholdId, householdId)
                            .eq(DinnerInviteCodeEntity::getCreatedBy, userId)
                            .isNull(DinnerInviteCodeEntity::getRevokedAt)
                            .set(DinnerInviteCodeEntity::getRevokedAt, deletedAt));
            deleteMembership(householdId, userId);
            return;
        }
        deleteHousehold(householdId);
    }

    private void deleteMembership(Long householdId, Long userId) {
        memberMapper.delete(Wrappers.<DinnerHouseholdMemberEntity>lambdaQuery()
                .eq(DinnerHouseholdMemberEntity::getHouseholdId, householdId)
                .eq(DinnerHouseholdMemberEntity::getUserId, userId));
    }

    private void deleteHousehold(Long householdId) {
        List<Long> menuIds = menuMapper.selectList(
                        Wrappers.<DinnerMenuEntity>lambdaQuery()
                                .eq(DinnerMenuEntity::getHouseholdId, householdId))
                .stream().map(DinnerMenuEntity::getId).toList();
        List<Long> recordIds = recordMapper.selectList(
                        Wrappers.<DinnerCookingRecordEntity>lambdaQuery()
                                .eq(DinnerCookingRecordEntity::getHouseholdId, householdId))
                .stream().map(DinnerCookingRecordEntity::getId).toList();
        List<Long> recipeIds = recipeMapper.selectList(
                        Wrappers.<DinnerRecipeEntity>lambdaQuery()
                                .eq(DinnerRecipeEntity::getHouseholdId, householdId))
                .stream().map(DinnerRecipeEntity::getId).toList();
        List<Long> methodIds = recipeIds.isEmpty()
                ? List.of()
                : recipeMethodMapper.selectList(
                                Wrappers.<DinnerRecipeMethodEntity>lambdaQuery()
                                        .in(DinnerRecipeMethodEntity::getRecipeId, recipeIds))
                        .stream().map(DinnerRecipeMethodEntity::getId).toList();

        if (!recordIds.isEmpty()) {
            snapshotMapper.delete(Wrappers.<DinnerRecordDishSnapshotEntity>lambdaQuery()
                    .in(DinnerRecordDishSnapshotEntity::getRecordId, recordIds));
        }
        recordMapper.delete(Wrappers.<DinnerCookingRecordEntity>lambdaQuery()
                .eq(DinnerCookingRecordEntity::getHouseholdId, householdId));
        if (!menuIds.isEmpty()) {
            actionMapper.delete(Wrappers.<DinnerMenuActionEntity>lambdaQuery()
                    .in(DinnerMenuActionEntity::getMenuId, menuIds));
            selectionMapper.delete(Wrappers.<DinnerMenuSelectionEntity>lambdaQuery()
                    .in(DinnerMenuSelectionEntity::getMenuId, menuIds));
        }
        menuMapper.delete(Wrappers.<DinnerMenuEntity>lambdaQuery()
                .eq(DinnerMenuEntity::getHouseholdId, householdId));
        inviteMapper.delete(Wrappers.<DinnerInviteCodeEntity>lambdaQuery()
                .eq(DinnerInviteCodeEntity::getHouseholdId, householdId));
        memberMapper.delete(Wrappers.<DinnerHouseholdMemberEntity>lambdaQuery()
                .eq(DinnerHouseholdMemberEntity::getHouseholdId, householdId));
        inventoryMapper.delete(Wrappers.<DinnerHouseholdInventoryEntity>lambdaQuery()
                .eq(DinnerHouseholdInventoryEntity::getHouseholdId, householdId));
        if (!methodIds.isEmpty()) {
            recipeMethodStepMapper.delete(
                    Wrappers.<DinnerRecipeMethodStepEntity>lambdaQuery()
                            .in(DinnerRecipeMethodStepEntity::getMethodId, methodIds));
        }
        if (!recipeIds.isEmpty()) {
            recipeMethodMapper.delete(Wrappers.<DinnerRecipeMethodEntity>lambdaQuery()
                    .in(DinnerRecipeMethodEntity::getRecipeId, recipeIds));
            recipeIngredientMapper.delete(
                    Wrappers.<DinnerRecipeIngredientEntity>lambdaQuery()
                            .in(DinnerRecipeIngredientEntity::getRecipeId, recipeIds));
            recipeMapper.update(null, Wrappers.<DinnerRecipeEntity>lambdaUpdate()
                    .in(DinnerRecipeEntity::getSourceRecipeId, recipeIds)
                    .set(DinnerRecipeEntity::getSourceRecipeId, null));
            recipeMapper.update(null, Wrappers.<DinnerRecipeEntity>lambdaUpdate()
                    .in(DinnerRecipeEntity::getRevisionOfRecipeId, recipeIds)
                    .set(DinnerRecipeEntity::getRevisionOfRecipeId, null));
        }
        recipeMapper.delete(Wrappers.<DinnerRecipeEntity>lambdaQuery()
                .eq(DinnerRecipeEntity::getHouseholdId, householdId));
        ingredientMapper.delete(Wrappers.<DinnerIngredientEntity>lambdaQuery()
                .eq(DinnerIngredientEntity::getHouseholdId, householdId));
        householdMapper.deleteById(householdId);
    }
}
