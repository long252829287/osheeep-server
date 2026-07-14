package com.osheeep.server.dinner.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
        LocalDateTime deletedAt = LocalDateTime.parse("2026-07-13T12:00:00");
        when(memberMapper.selectByUserIdForUpdate(7L)).thenReturn(membership);
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L));
        when(memberMapper.selectCount(any())).thenReturn(2L);

        service.removeUser(7L, deletedAt);

        InOrder lockOrder = inOrder(memberMapper, householdMapper);
        lockOrder.verify(memberMapper).selectByUserIdForUpdate(7L);
        lockOrder.verify(householdMapper).selectByIdForUpdate(11L);

        ArgumentCaptor<Wrapper<DinnerHouseholdMemberEntity>> memberCount = wrapperCaptor();
        verify(memberMapper).selectCount(memberCount.capture());
        assertEqualsCondition(memberCount.getValue(), "household_id", 11L);
        assertOnlyParameterValues(memberCount.getValue(), 11L);

        ArgumentCaptor<Wrapper<DinnerInviteCodeEntity>> inviteUpdate = wrapperCaptor();
        verify(inviteMapper).update(isNull(), inviteUpdate.capture());
        assertEqualsCondition(inviteUpdate.getValue(), "household_id", 11L);
        assertEqualsCondition(inviteUpdate.getValue(), "created_by", 7L);
        assertThat(inviteUpdate.getValue().getSqlSegment()).contains("revoked_at IS NULL");
        assertUpdateValue(inviteUpdate.getValue(), "revoked_at", deletedAt);
        assertOnlyParameterValues(inviteUpdate.getValue(), 11L, 7L, deletedAt);

        ArgumentCaptor<Wrapper<DinnerHouseholdMemberEntity>> memberDelete = wrapperCaptor();
        verify(memberMapper).delete(memberDelete.capture());
        assertEqualsCondition(memberDelete.getValue(), "household_id", 11L);
        assertEqualsCondition(memberDelete.getValue(), "user_id", 7L);
        assertOnlyParameterValues(memberDelete.getValue(), 11L, 7L);
        verify(memberMapper, never()).deleteById(anyLong());
        verify(householdMapper, never()).deleteById(anyLong());
        verifyNoInteractions(menuMapper, selectionMapper, actionMapper, recipeMapper,
                recordMapper, snapshotMapper);
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

        ArgumentCaptor<Wrapper<DinnerHouseholdMemberEntity>> memberCount = wrapperCaptor();
        verify(memberMapper).selectCount(memberCount.capture());
        assertEqualsCondition(memberCount.getValue(), "household_id", 11L);
        assertOnlyParameterValues(memberCount.getValue(), 11L);

        ArgumentCaptor<Wrapper<DinnerMenuEntity>> menuSelect = wrapperCaptor();
        verify(menuMapper).selectList(menuSelect.capture());
        assertEqualsCondition(menuSelect.getValue(), "household_id", 11L);
        assertOnlyParameterValues(menuSelect.getValue(), 11L);
        ArgumentCaptor<Wrapper<DinnerCookingRecordEntity>> recordSelect = wrapperCaptor();
        verify(recordMapper).selectList(recordSelect.capture());
        assertEqualsCondition(recordSelect.getValue(), "household_id", 11L);
        assertOnlyParameterValues(recordSelect.getValue(), 11L);

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

        ArgumentCaptor<Wrapper<DinnerRecordDishSnapshotEntity>> snapshotDelete = wrapperCaptor();
        verify(snapshotMapper).delete(snapshotDelete.capture());
        assertInCondition(snapshotDelete.getValue(), "record_id", 41L);
        assertOnlyParameterValues(snapshotDelete.getValue(), 41L);
        ArgumentCaptor<Wrapper<DinnerCookingRecordEntity>> recordDelete = wrapperCaptor();
        verify(recordMapper).delete(recordDelete.capture());
        assertEqualsCondition(recordDelete.getValue(), "household_id", 11L);
        assertOnlyParameterValues(recordDelete.getValue(), 11L);
        ArgumentCaptor<Wrapper<DinnerMenuActionEntity>> actionDelete = wrapperCaptor();
        verify(actionMapper).delete(actionDelete.capture());
        assertInCondition(actionDelete.getValue(), "menu_id", 21L);
        assertOnlyParameterValues(actionDelete.getValue(), 21L);
        ArgumentCaptor<Wrapper<DinnerMenuSelectionEntity>> selectionDelete = wrapperCaptor();
        verify(selectionMapper).delete(selectionDelete.capture());
        assertInCondition(selectionDelete.getValue(), "menu_id", 21L);
        assertOnlyParameterValues(selectionDelete.getValue(), 21L);
        ArgumentCaptor<Wrapper<DinnerMenuEntity>> menuDelete = wrapperCaptor();
        verify(menuMapper).delete(menuDelete.capture());
        assertEqualsCondition(menuDelete.getValue(), "household_id", 11L);
        assertOnlyParameterValues(menuDelete.getValue(), 11L);
        ArgumentCaptor<Wrapper<DinnerInviteCodeEntity>> inviteDelete = wrapperCaptor();
        verify(inviteMapper).delete(inviteDelete.capture());
        assertEqualsCondition(inviteDelete.getValue(), "household_id", 11L);
        assertOnlyParameterValues(inviteDelete.getValue(), 11L);
        ArgumentCaptor<Wrapper<DinnerHouseholdMemberEntity>> memberDelete = wrapperCaptor();
        verify(memberMapper).delete(memberDelete.capture());
        assertEqualsCondition(memberDelete.getValue(), "household_id", 11L);
        assertOnlyParameterValues(memberDelete.getValue(), 11L);
        ArgumentCaptor<Wrapper<DinnerRecipeEntity>> recipeDelete = wrapperCaptor();
        verify(recipeMapper).delete(recipeDelete.capture());
        assertEqualsCondition(recipeDelete.getValue(), "household_id", 11L);
        assertOnlyParameterValues(recipeDelete.getValue(), 11L);
    }

    @Test
    void missingHouseholdOnlyRemovesDeletingUsersMembership() {
        when(memberMapper.selectByUserIdForUpdate(7L))
                .thenReturn(membership(31L, 11L, 7L));
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(null);

        service.removeUser(7L, LocalDateTime.parse("2026-07-13T12:00:00"));

        ArgumentCaptor<Wrapper<DinnerHouseholdMemberEntity>> memberDelete = wrapperCaptor();
        verify(memberMapper).delete(memberDelete.capture());
        assertEqualsCondition(memberDelete.getValue(), "household_id", 11L);
        assertEqualsCondition(memberDelete.getValue(), "user_id", 7L);
        assertOnlyParameterValues(memberDelete.getValue(), 11L, 7L);
        verifyNoInteractions(inviteMapper, menuMapper, selectionMapper, actionMapper,
                recipeMapper, recordMapper, snapshotMapper);
    }

    @Test
    void lastMemberWithNoMenusOrRecordsSkipsIdScopedChildDeletes() {
        when(memberMapper.selectByUserIdForUpdate(7L))
                .thenReturn(membership(31L, 11L, 7L));
        when(householdMapper.selectByIdForUpdate(11L)).thenReturn(household(11L));
        when(memberMapper.selectCount(any())).thenReturn(1L);
        when(menuMapper.selectList(any())).thenReturn(List.of());
        when(recordMapper.selectList(any())).thenReturn(List.of());

        service.removeUser(7L, LocalDateTime.parse("2026-07-13T12:00:00"));

        verify(snapshotMapper, never()).delete(any());
        verify(actionMapper, never()).delete(any());
        verify(selectionMapper, never()).delete(any());
        ArgumentCaptor<Wrapper<DinnerCookingRecordEntity>> recordDelete = wrapperCaptor();
        verify(recordMapper).delete(recordDelete.capture());
        assertEqualsCondition(recordDelete.getValue(), "household_id", 11L);
        assertOnlyParameterValues(recordDelete.getValue(), 11L);
        ArgumentCaptor<Wrapper<DinnerMenuEntity>> menuDelete = wrapperCaptor();
        verify(menuMapper).delete(menuDelete.capture());
        assertEqualsCondition(menuDelete.getValue(), "household_id", 11L);
        assertOnlyParameterValues(menuDelete.getValue(), 11L);
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private static void assertEqualsCondition(
            Wrapper<?> wrapper, String column, Object expectedValue) {
        Matcher matcher = Pattern.compile(
                        Pattern.quote(column)
                                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.([^}]+)}")
                .matcher(wrapper.getSqlSegment());
        assertThat(matcher.find())
                .as("%s equality condition in %s", column, wrapper.getSqlSegment())
                .isTrue();
        assertThat(parameterValues(wrapper))
                .containsEntry(matcher.group(1), expectedValue);
    }

    private static void assertInCondition(
            Wrapper<?> wrapper, String column, Object... expectedValues) {
        Matcher condition = Pattern.compile(
                        Pattern.quote(column) + "\\s+IN\\s*\\(([^)]*)\\)")
                .matcher(wrapper.getSqlSegment());
        assertThat(condition.find())
                .as("%s IN condition in %s", column, wrapper.getSqlSegment())
                .isTrue();
        Matcher parameters = Pattern.compile(
                        "#\\{ew\\.paramNameValuePairs\\.([^}]+)}")
                .matcher(condition.group(1));
        List<Object> actualValues = new ArrayList<>();
        while (parameters.find()) {
            actualValues.add(parameterValues(wrapper).get(parameters.group(1)));
        }
        assertThat(actualValues).containsExactlyInAnyOrder(expectedValues);
    }

    private static void assertUpdateValue(
            Wrapper<?> wrapper, String column, Object expectedValue) {
        assertThat(wrapper).isInstanceOf(LambdaUpdateWrapper.class);
        String sqlSet = ((LambdaUpdateWrapper<?>) wrapper).getSqlSet();
        Matcher matcher = Pattern.compile(
                        Pattern.quote(column)
                                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.([^}]+)}")
                .matcher(sqlSet);
        assertThat(matcher.find()).as("%s update in %s", column, sqlSet).isTrue();
        assertThat(parameterValues(wrapper))
                .containsEntry(matcher.group(1), expectedValue);
    }

    private static java.util.Map<String, Object> parameterValues(Wrapper<?> wrapper) {
        assertThat(wrapper).isInstanceOf(AbstractWrapper.class);
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }

    private static void assertOnlyParameterValues(
            Wrapper<?> wrapper, Object... expectedValues) {
        assertThat(parameterValues(wrapper).values())
                .containsExactlyInAnyOrder(expectedValues);
    }
}
