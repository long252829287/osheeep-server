package com.osheeep.server.dinner.household;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.entity.DinnerInviteCodeEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.household.mapper.DinnerInviteCodeMapper;
import com.osheeep.server.dinner.menu.entity.DinnerMenuActionEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuActionMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
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
            memberMapper.deleteById(membership.getId());
            return;
        }
        long memberCount = memberMapper.selectCount(
                Wrappers.<DinnerHouseholdMemberEntity>lambdaQuery()
                        .eq(DinnerHouseholdMemberEntity::getHouseholdId, householdId));
        if (memberCount > 1) {
            inviteMapper.update(null,
                    Wrappers.<DinnerInviteCodeEntity>lambdaUpdate()
                            .eq(DinnerInviteCodeEntity::getCreatedBy, userId)
                            .isNull(DinnerInviteCodeEntity::getRevokedAt)
                            .set(DinnerInviteCodeEntity::getRevokedAt, deletedAt));
            memberMapper.deleteById(membership.getId());
            return;
        }
        deleteHousehold(householdId);
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
        recipeMapper.delete(Wrappers.<DinnerRecipeEntity>lambdaQuery()
                .eq(DinnerRecipeEntity::getHouseholdId, householdId));
        householdMapper.deleteById(householdId);
    }
}
