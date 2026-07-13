package com.osheeep.server.dinner.household;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerAccountCleanupServiceTest {

    @Mock private DinnerHouseholdMapper householdMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerInviteCodeMapper inviteMapper;
    @Mock private DinnerMenuMapper menuMapper;
    @Mock private DinnerMenuSelectionMapper selectionMapper;
    @Mock private DinnerMenuActionMapper actionMapper;
    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerCookingRecordMapper recordMapper;
    @Mock private DinnerRecordDishSnapshotMapper snapshotMapper;

    private DinnerAccountCleanupService service;

    @BeforeAll
    static void initializeMybatisTableMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        List.of(
                        DinnerHouseholdMemberEntity.class,
                        DinnerInviteCodeEntity.class,
                        DinnerMenuEntity.class,
                        DinnerMenuSelectionEntity.class,
                        DinnerMenuActionEntity.class,
                        DinnerRecipeEntity.class,
                        DinnerCookingRecordEntity.class,
                        DinnerRecordDishSnapshotEntity.class)
                .forEach(entityType -> TableInfoHelper.initTableInfo(assistant, entityType));
    }

    @BeforeEach
    void setUp() {
        service = new DinnerAccountCleanupService(
                householdMapper, memberMapper, inviteMapper, menuMapper, selectionMapper,
                actionMapper, recipeMapper, recordMapper, snapshotMapper);
    }

    @Test
    void remainingMemberKeepsHouseholdHistoryAndOnlyRemovesDeletingMember() {
        DinnerHouseholdMemberEntity membership = membership(31L, 11L, 7L);
        when(memberMapper.selectByUserIdForUpdate(7L)).thenReturn(membership);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L));
        when(memberMapper.selectCount(any())).thenReturn(2L);

        service.removeUser(7L, LocalDateTime.parse("2026-07-13T12:00:00"));

        verify(inviteMapper).update(isNull(), any());
        verify(memberMapper).deleteById(31L);
        verify(householdMapper, never()).deleteById(anyLong());
        verify(menuMapper, never()).delete(any());
    }

    @Test
    void lastMemberDeletesHouseholdDataInForeignKeySafeOrder() {
        DinnerHouseholdMemberEntity membership = membership(31L, 11L, 7L);
        DinnerMenuEntity menu = new DinnerMenuEntity();
        menu.setId(21L);
        DinnerCookingRecordEntity record = new DinnerCookingRecordEntity();
        record.setId(41L);
        when(memberMapper.selectByUserIdForUpdate(7L)).thenReturn(membership);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L));
        when(memberMapper.selectCount(any())).thenReturn(1L);
        when(menuMapper.selectList(any())).thenReturn(List.of(menu));
        when(recordMapper.selectList(any())).thenReturn(List.of(record));

        service.removeUser(7L, LocalDateTime.parse("2026-07-13T12:00:00"));

        InOrder order = inOrder(snapshotMapper, recordMapper, actionMapper,
                selectionMapper, menuMapper, inviteMapper, memberMapper,
                recipeMapper, householdMapper);
        order.verify(snapshotMapper).delete(any());
        order.verify(recordMapper).delete(any());
        order.verify(actionMapper).delete(any());
        order.verify(selectionMapper).delete(any());
        order.verify(menuMapper).delete(any());
        order.verify(inviteMapper).delete(any());
        order.verify(memberMapper).delete(any());
        order.verify(recipeMapper).delete(any());
        order.verify(householdMapper).deleteById(11L);
    }

    @Test
    void removeUserDoesNothingWhenMembershipDoesNotExist() {
        when(memberMapper.selectByUserIdForUpdate(7L)).thenReturn(null);

        service.removeUser(7L, LocalDateTime.parse("2026-07-13T12:00:00"));

        verifyNoInteractions(householdMapper, inviteMapper, menuMapper, selectionMapper,
                actionMapper, recipeMapper, recordMapper, snapshotMapper);
    }

    private DinnerHouseholdMemberEntity membership(Long id, Long householdId, Long userId) {
        DinnerHouseholdMemberEntity membership = new DinnerHouseholdMemberEntity();
        membership.setId(id);
        membership.setHouseholdId(householdId);
        membership.setUserId(userId);
        return membership;
    }

    private DinnerHouseholdEntity household(Long id) {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(id);
        return household;
    }
}
