package com.osheeep.server.dinner.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.LockedHouseholdContext;
import com.osheeep.server.dinner.menu.BusinessDateResolver;
import com.osheeep.server.dinner.menu.DinnerMenuService;
import com.osheeep.server.dinner.menu.dto.TodayMenuResponse;
import com.osheeep.server.dinner.menu.entity.DinnerMenuActionEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuActionMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodStepSnapshotResponse;
import com.osheeep.server.dinner.record.entity.DinnerCookingRecordEntity;
import com.osheeep.server.dinner.record.entity.DinnerRecordDishSnapshotEntity;
import com.osheeep.server.dinner.record.mapper.DinnerCookingRecordMapper;
import com.osheeep.server.dinner.record.mapper.DinnerRecordDishSnapshotMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class DinnerRecordServiceTest {

    @Mock private DinnerHouseholdAccessService householdAccessService;
    @Mock private DinnerMenuMapper menuMapper;
    @Mock private DinnerMenuSelectionMapper selectionMapper;
    @Mock private DinnerMenuActionMapper actionMapper;
    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerCookingRecordMapper recordMapper;
    @Mock private DinnerRecordDishSnapshotMapper snapshotMapper;
    @Mock private DinnerMenuService menuService;
    @Mock private DinnerRecordSnapshotAssembler snapshotAssembler;

    private DinnerRecordService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                DinnerCookingRecordEntity.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T11:00:00Z"), ZoneOffset.UTC);
        service = new DinnerRecordService(
                householdAccessService, menuMapper, selectionMapper, actionMapper,
                recordMapper, snapshotMapper, menuService, snapshotAssembler,
                new DinnerRecordSnapshotJsonCodec(new ObjectMapper()),
                new BusinessDateResolver(), clock);
    }

    @Test
    void repeatedCompleteReturnsTheExistingRecord() {
        DinnerMenuEntity menu = menu("CONFIRMED", 5L);
        LockedHouseholdContext context = stubContext(menu);
        when(recordMapper.selectOne(any())).thenReturn(record(91L, 31L));
        when(menuService.responseForLockedContext(7L, context, menu))
                .thenReturn(today(91L));

        var result = service.complete(7L, 4L, "00000000-0000-4000-8000-000000000011");

        assertThat(result.recordId()).isEqualTo(91L);
        verify(recordMapper, never()).insert(any(DinnerCookingRecordEntity.class));
        verify(snapshotMapper, never()).insert(any(DinnerRecordDishSnapshotEntity.class));
        verifyNoInteractions(snapshotAssembler);
        InOrder order = inOrder(householdAccessService, menuMapper, recordMapper);
        order.verify(householdAccessService).lockActiveHouseholdContext(7L);
        order.verify(menuMapper).selectByHouseholdAndDateForUpdate(
                11L, LocalDate.of(2026, 7, 11));
        order.verify(recordMapper).selectOne(any());
        verify(menuService, never()).today(any());
        verify(householdAccessService, never()).requireActiveHousehold(any());
    }

    @Test
    void staleHouseholdContextStopsCompletionBeforeMenuAndRecordLookups() {
        when(householdAccessService.lockActiveHouseholdContext(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThatThrownBy(() -> service.complete(
                7L, 5L, "00000000-0000-4000-8000-000000000090"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        verifyNoInteractions(
                menuMapper, selectionMapper, actionMapper, recordMapper, snapshotMapper,
                menuService, snapshotAssembler);
    }

    @Test
    void completeMapsMenuLockFailureToVersionConflict() {
        LockedHouseholdContext context = mock(LockedHouseholdContext.class);
        when(context.access()).thenReturn(access());
        when(householdAccessService.lockActiveHouseholdContext(7L))
                .thenReturn(context);
        when(menuMapper.selectByHouseholdAndDateForUpdate(
                11L, LocalDate.of(2026, 7, 11)))
                .thenThrow(new CannotAcquireLockException("timeout"));

        assertThatThrownBy(() -> service.complete(
                7L, 5L, "00000000-0000-4000-8000-000000000095"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_MENU_VERSION_CONFLICT));

        verifyNoInteractions(
                recordMapper, selectionMapper, actionMapper, snapshotMapper,
                menuService, snapshotAssembler);
    }

    @Test
    void completeRejectsACompletedMenuBeforeTheMembershipVisibilityWindow() {
        LocalDateTime visibleFrom = LocalDateTime.of(2026, 7, 11, 10, 30);
        DinnerMenuEntity menu = menu("COMPLETED", 6L);
        menu.setCompletedAt(visibleFrom.minusNanos(1));
        stubContext(menu, access(visibleFrom));

        assertThatThrownBy(() -> service.complete(
                7L, 6L, "00000000-0000-4000-8000-000000000091"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verifyNoInteractions(
                recordMapper, selectionMapper, actionMapper, snapshotMapper,
                menuService, snapshotAssembler);
    }

    @Test
    void completeFailsClosedWhenACompletedMenuHasNoCompletionTime() {
        DinnerMenuEntity menu = menu("COMPLETED", 6L);
        menu.setCompletedAt(null);
        stubContext(menu, access(LocalDateTime.of(2026, 7, 11, 10, 30)));

        assertThatThrownBy(() -> service.complete(
                7L, 6L, "00000000-0000-4000-8000-000000000092"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verifyNoInteractions(
                recordMapper, selectionMapper, actionMapper, snapshotMapper,
                menuService, snapshotAssembler);
    }

    @Test
    void completeRejectsANewRecordBeforeAFutureMembershipVisibilityWindow() {
        DinnerMenuEntity menu = menu("CONFIRMED", 5L);
        stubContext(menu, access(LocalDateTime.of(2026, 7, 11, 12, 0)));
        when(recordMapper.selectOne(any())).thenReturn(null);
        when(selectionMapper.selectList(any())).thenReturn(List.of());
        when(snapshotAssembler.assemble(11L, List.of())).thenReturn(List.of());

        assertThatThrownBy(() -> service.complete(
                7L, 5L, "00000000-0000-4000-8000-000000000094"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(recordMapper, never()).insert(any(DinnerCookingRecordEntity.class));
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
        verify(actionMapper, never()).insert(any(DinnerMenuActionEntity.class));
        verifyNoInteractions(snapshotMapper, menuService);
    }

    @Test
    void repeatedCompleteAtTheExactVisibilityBoundaryReturnsTheExistingRecord() {
        LocalDateTime visibleFrom = LocalDateTime.of(2026, 7, 11, 10, 30);
        DinnerMenuEntity menu = menu("COMPLETED", 6L);
        menu.setCompletedAt(visibleFrom);
        DinnerCookingRecordEntity existing = record(91L, 31L);
        existing.setCompletedAt(visibleFrom);
        LockedHouseholdContext context = stubContext(menu, access(visibleFrom));
        when(recordMapper.selectOne(any())).thenReturn(existing);
        when(menuService.responseForLockedContext(7L, context, menu))
                .thenReturn(today(91L));

        var result = service.complete(
                7L, 0L, "00000000-0000-4000-8000-000000000093");

        assertThat(result.recordId()).isEqualTo(91L);
        verify(recordMapper, never()).insert(any(DinnerCookingRecordEntity.class));
        verify(menuService, never()).today(any());
        verify(householdAccessService, never()).requireActiveHousehold(any());
    }

    @Test
    void completeCreatesOneRecordAndDishSnapshots() {
        DinnerMenuEntity menu = menu("CONFIRMED", 5L);
        LockedHouseholdContext context = stubContext(menu);
        when(recordMapper.selectOne(any())).thenReturn(null);
        List<DinnerMenuSelectionEntity> selections = List.of(
                selection(7L, 1L), selection(8L, 1L), selection(7L, 14L));
        when(selectionMapper.selectList(any())).thenReturn(selections);
        when(snapshotAssembler.assemble(11L, selections)).thenReturn(List.of(
                systemDraft(1L, Set.of(7L, 8L)),
                householdDraft(14L, Set.of(7L))));
        when(recordMapper.insert(any(DinnerCookingRecordEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerCookingRecordEntity>getArgument(0).setId(91L);
            return 1;
        });
        when(menuService.responseForLockedContext(7L, context, menu))
                .thenReturn(today(91L));

        var result = service.complete(7L, 5L, "00000000-0000-4000-8000-000000000012");

        assertThat(result.recordId()).isEqualTo(91L);
        assertThat(menu.getStatus()).isEqualTo("COMPLETED");
        assertThat(menu.getVersion()).isEqualTo(6L);
        verify(recordMapper).insert(any(DinnerCookingRecordEntity.class));
        ArgumentCaptor<DinnerRecordDishSnapshotEntity> inserted =
                ArgumentCaptor.forClass(DinnerRecordDishSnapshotEntity.class);
        verify(snapshotMapper, times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).satisfiesExactly(
                system -> {
                    assertThat(system.getRecipeScope()).isEqualTo("SYSTEM");
                    assertThat(system.getRecipeVersion()).isEqualTo(1L);
                    assertThat(system.getServings()).isNull();
                    assertThat(system.getMethodId()).isNull();
                    assertThat(system.getIngredientsJson()).contains("系统食材");
                    assertThat(system.getSortOrder()).isZero();
                },
                family -> {
                    assertThat(family.getRecipeScope()).isEqualTo("HOUSEHOLD");
                    assertThat(family.getRecipeVersion()).isEqualTo(8L);
                    assertThat(family.getServings()).isEqualTo(2);
                    assertThat(family.getMethodId()).isEqualTo(21L);
                    assertThat(family.getMethodStepsJson()).contains("翻炒", "盛盘");
                    assertThat(family.getIngredientsJson()).contains("鸡蛋");
                    assertThat(family.getSortOrder()).isEqualTo(1);
                });
        verify(actionMapper).insert(any(DinnerMenuActionEntity.class));
        verify(menuService, never()).today(any());
        verify(householdAccessService, never()).requireActiveHousehold(any());
    }

    @Test
    void duplicateCompletionUsesLockedContextResponseWithoutReadAuthorization() {
        DinnerMenuEntity menu = menu("CONFIRMED", 5L);
        LockedHouseholdContext context = stubContext(menu);
        DinnerCookingRecordEntity winner = record(92L, 31L);
        when(recordMapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(winner);
        when(selectionMapper.selectList(any())).thenReturn(List.of());
        when(snapshotAssembler.assemble(11L, List.of())).thenReturn(List.of());
        when(recordMapper.insert(any(DinnerCookingRecordEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(menuService.responseForLockedContext(7L, context, menu))
                .thenReturn(today(92L));

        var result = service.complete(
                7L, 5L, "00000000-0000-4000-8000-000000000096");

        assertThat(result.recordId()).isEqualTo(92L);
        verify(menuService).responseForLockedContext(7L, context, menu);
        verify(menuService, never()).today(any());
        verify(householdAccessService, never()).requireActiveHousehold(any());
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
        verifyNoInteractions(snapshotMapper);
    }

    @Test
    void aggregateValidationFailureHappensBeforeAnyCompletionWrite() {
        DinnerMenuEntity menu = menu("CONFIRMED", 5L);
        stubContext(menu);
        when(recordMapper.selectOne(any())).thenReturn(null);
        List<DinnerMenuSelectionEntity> selections = List.of(selection(7L, 14L));
        when(selectionMapper.selectList(any())).thenReturn(selections);
        when(snapshotAssembler.assemble(11L, selections))
                .thenThrow(new BusinessException(ErrorCode.DINNER_RECIPE_INVALID));

        assertThatThrownBy(() -> service.complete(
                7L, 5L, "00000000-0000-4000-8000-000000000013"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_INVALID));

        verify(recordMapper, never()).insert(any(DinnerCookingRecordEntity.class));
        verify(snapshotMapper, never()).insert(any(DinnerRecordDishSnapshotEntity.class));
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
        verify(actionMapper, never()).insert(any(DinnerMenuActionEntity.class));
    }

    @Test
    void everySnapshotDraftIsEncodedBeforeAnyCompletionWrite() {
        DinnerMenuEntity menu = menu("CONFIRMED", 5L);
        stubContext(menu);
        when(recordMapper.selectOne(any())).thenReturn(null);
        List<DinnerMenuSelectionEntity> selections = List.of(
                selection(7L, 1L), selection(7L, 14L));
        when(selectionMapper.selectList(any())).thenReturn(selections);
        when(snapshotAssembler.assemble(11L, selections)).thenReturn(List.of(
                systemDraft(1L, Set.of(7L)),
                householdDraftWithInvalidStep(14L, Set.of(7L))));

        assertThatThrownBy(() -> service.complete(
                7L, 5L, "00000000-0000-4000-8000-000000000016"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid dinner record snapshot JSON");

        verify(recordMapper, never()).insert(any(DinnerCookingRecordEntity.class));
        verify(snapshotMapper, never()).insert(any(DinnerRecordDishSnapshotEntity.class));
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
        verify(actionMapper, never()).insert(any(DinnerMenuActionEntity.class));
    }

    @Test
    void snapshotInsertFailureEscapesBeforeMenuAndActionWrites() {
        DinnerMenuEntity menu = menu("CONFIRMED", 5L);
        stubContext(menu);
        when(recordMapper.selectOne(any())).thenReturn(null);
        List<DinnerMenuSelectionEntity> selections = List.of(
                selection(7L, 1L), selection(7L, 14L));
        when(selectionMapper.selectList(any())).thenReturn(selections);
        when(snapshotAssembler.assemble(11L, selections)).thenReturn(List.of(
                systemDraft(1L, Set.of(7L)), householdDraft(14L, Set.of(7L))));
        when(recordMapper.insert(any(DinnerCookingRecordEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerCookingRecordEntity>getArgument(0).setId(91L);
            return 1;
        });
        when(snapshotMapper.insert(any(DinnerRecordDishSnapshotEntity.class)))
                .thenReturn(1)
                .thenThrow(new IllegalStateException("snapshot write failed"));

        assertThatThrownBy(() -> service.complete(
                7L, 5L, "00000000-0000-4000-8000-000000000014"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("snapshot write failed");

        verify(snapshotMapper, times(2)).insert(any(DinnerRecordDishSnapshotEntity.class));
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
        verify(actionMapper, never()).insert(any(DinnerMenuActionEntity.class));
    }

    @Test
    void duplicateRecordWithoutWinnerRethrowsDuplicateFailure() {
        DinnerMenuEntity menu = menu("CONFIRMED", 5L);
        stubContext(menu);
        when(recordMapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(null);
        List<DinnerMenuSelectionEntity> selections = List.of(selection(7L, 1L));
        when(selectionMapper.selectList(any())).thenReturn(selections);
        when(snapshotAssembler.assemble(11L, selections)).thenReturn(List.of(
                systemDraft(1L, Set.of(7L))));
        when(recordMapper.insert(any(DinnerCookingRecordEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.complete(
                7L, 5L, "00000000-0000-4000-8000-000000000015"))
                .isInstanceOf(DuplicateKeyException.class);

        verify(snapshotMapper, never()).insert(any(DinnerRecordDishSnapshotEntity.class));
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
    }

    @ParameterizedTest(name = "{0} membership cannot list its former household records")
    @MethodSource("inactiveMembershipStatuses")
    void listRejectsInactiveMembership(String membershipStatus) {
        when(householdAccessService.requireActiveHousehold(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThatThrownBy(() -> service.list(7L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThat(membershipStatus).isIn("LEFT", "REMOVED");
        verifyNoInteractions(recordMapper, snapshotMapper);
    }

    @ParameterizedTest(name = "{0} membership cannot deep-link its former household record")
    @MethodSource("inactiveMembershipStatuses")
    void detailRejectsInactiveMembership(String membershipStatus) {
        when(householdAccessService.requireActiveHousehold(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThatThrownBy(() -> service.detail(7L, 91L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThat(membershipStatus).isIn("LEFT", "REMOVED");
        verifyNoInteractions(recordMapper, snapshotMapper);
    }

    @ParameterizedTest(name = "ACTIVE membership rejects {0} household records")
    @MethodSource("unavailableHouseholds")
    void listRejectsActiveMembershipWithoutActiveHousehold(String reason) {
        when(householdAccessService.requireActiveHousehold(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThatThrownBy(() -> service.list(7L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThat(reason).isNotBlank();
        verifyNoInteractions(recordMapper, snapshotMapper);
    }

    @Test
    void listsRecordsForTheCurrentHousehold() {
        when(householdAccessService.requireActiveHousehold(7L)).thenReturn(access());
        DinnerCookingRecordEntity record = record(91L, 31L);
        record.setHouseholdId(11L);
        record.setRecordDate(LocalDate.of(2026, 7, 11));
        record.setCompletedBy(7L);
        when(recordMapper.selectList(any())).thenReturn(List.of(record));
        when(snapshotMapper.selectCount(any())).thenReturn(2L);

        var result = service.list(7L);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(91L);
            assertThat(item.dishCount()).isEqualTo(2);
        });
    }

    @Test
    void listStartsAtCurrentMembershipHistoryVisibilityWindow() {
        LocalDateTime visibleFrom = LocalDateTime.of(2026, 7, 11, 10, 30);
        DinnerCookingRecordEntity hidden = record(90L, 30L);
        hidden.setHouseholdId(11L);
        hidden.setRecordDate(LocalDate.of(2026, 7, 11));
        hidden.setCompletedBy(8L);
        hidden.setCompletedAt(visibleFrom.minusMinutes(1));
        DinnerCookingRecordEntity visible = record(91L, 31L);
        visible.setHouseholdId(11L);
        visible.setRecordDate(LocalDate.of(2026, 7, 11));
        visible.setCompletedBy(7L);
        visible.setCompletedAt(visibleFrom);
        when(householdAccessService.requireActiveHousehold(7L))
                .thenReturn(access(visibleFrom));
        when(recordMapper.selectList(any())).thenAnswer(invocation -> {
            Wrapper<?> query = invocation.getArgument(0);
            String sql = query.getSqlSegment().toLowerCase(Locale.ROOT);
            return sql.contains("completed_at") && sql.contains(">=")
                    ? List.of(visible)
                    : List.of(hidden, visible);
        });
        when(snapshotMapper.selectCount(any())).thenReturn(1L);

        var result = service.list(7L);

        assertThat(result).extracting(item -> item.id()).containsExactly(91L);
    }

    @Test
    void detailDeepLinkRejectsRecordBeforeCurrentMembershipVisibilityWindow() {
        LocalDateTime visibleFrom = LocalDateTime.of(2026, 7, 11, 10, 30);
        DinnerCookingRecordEntity record = record(91L, 31L);
        record.setHouseholdId(11L);
        record.setRecordDate(LocalDate.of(2026, 7, 11));
        record.setCompletedBy(8L);
        record.setCompletedAt(visibleFrom.minusNanos(1));
        when(householdAccessService.requireActiveHousehold(7L))
                .thenReturn(access(visibleFrom));
        when(recordMapper.selectById(91L)).thenReturn(record);

        assertThatThrownBy(() -> service.detail(7L, 91L))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(snapshotMapper);
    }

    @Test
    void detailAllowsRecordAtTheExactMembershipVisibilityBoundary() {
        LocalDateTime visibleFrom = LocalDateTime.of(2026, 7, 11, 10, 30);
        DinnerCookingRecordEntity record = record(91L, 31L);
        record.setCompletedAt(visibleFrom);
        when(householdAccessService.requireActiveHousehold(7L))
                .thenReturn(access(visibleFrom));
        when(recordMapper.selectById(91L)).thenReturn(record);
        when(snapshotMapper.selectList(any())).thenReturn(List.of());

        var result = service.detail(7L, 91L);

        assertThat(result.id()).isEqualTo(91L);
        assertThat(result.completedAt())
                .isEqualTo(visibleFrom.toInstant(ZoneOffset.UTC));
    }

    @Test
    void detailFailsClosedWhenRecordCompletionTimeIsMissing() {
        DinnerCookingRecordEntity record = record(91L, 31L);
        record.setCompletedAt(null);
        when(householdAccessService.requireActiveHousehold(7L)).thenReturn(access());
        when(recordMapper.selectById(91L)).thenReturn(record);

        assertThatThrownBy(() -> service.detail(7L, 91L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verifyNoInteractions(snapshotMapper);
    }

    @Test
    void legacyRecordDetailNormalizesNewFieldsWithoutCurrentRecipeLookup() {
        when(householdAccessService.requireActiveHousehold(7L)).thenReturn(access());
        DinnerCookingRecordEntity record = record(91L, 31L);
        record.setHouseholdId(11L);
        record.setRecordDate(LocalDate.of(2026, 7, 11));
        record.setCompletedBy(7L);
        when(recordMapper.selectById(91L)).thenReturn(record);
        DinnerRecordDishSnapshotEntity snapshot = new DinnerRecordDishSnapshotEntity();
        snapshot.setRecipeId(1L);
        snapshot.setName("番茄炒蛋");
        snapshot.setCategory("家常菜");
        snapshot.setFlavor("酸甜");
        snapshot.setEstimatedMinutes(10);
        snapshot.setSelectedByUserIds("[7,8]");
        snapshot.setSortOrder(0);
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot));

        var result = service.detail(7L, 91L);

        assertThat(result.dishes()).singleElement().satisfies(item -> {
            assertThat(item.source()).isEqualTo("BOTH");
            assertThat(item.scope()).isEqualTo("SYSTEM");
            assertThat(item.recipeVersion()).isEqualTo(1L);
            assertThat(item.servings()).isNull();
            assertThat(item.method()).isNull();
            assertThat(item.ingredients()).isEmpty();
            assertThatThrownBy(() -> item.ingredients().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        });
        verifyNoInteractions(recipeMapper);
    }

    @Test
    void householdRecordDetailReadsMethodAndIngredientsOnlyFromStoredSnapshot() {
        when(householdAccessService.requireActiveHousehold(7L)).thenReturn(access());
        DinnerCookingRecordEntity record = record(91L, 31L);
        record.setHouseholdId(11L);
        when(recordMapper.selectById(91L)).thenReturn(record);
        DinnerRecordDishSnapshotEntity snapshot = householdSnapshot();
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot));

        var dish = service.detail(7L, 91L).dishes().getFirst();

        assertThat(dish.scope()).isEqualTo("HOUSEHOLD");
        assertThat(dish.recipeVersion()).isEqualTo(8L);
        assertThat(dish.method().id()).isEqualTo(21L);
        assertThat(dish.method().steps()).extracting(step -> step.sortOrder())
                .containsExactly(1, 0);
        assertThat(dish.ingredients()).singleElement()
                .satisfies(ingredient -> assertThat(ingredient.quantity()).isNull());
        verifyNoInteractions(recipeMapper);
    }

    @Test
    void partialMethodMetadataFailsSafely() {
        DinnerRecordDishSnapshotEntity snapshot = householdSnapshot();
        snapshot.setMethodName(null);
        stubDetail(snapshot);

        assertThatThrownBy(() -> service.detail(7L, 91L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Incomplete dinner record dish snapshot");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteHouseholdSnapshots")
    void incompleteExplicitHouseholdSnapshotFailsSafely(SnapshotCase snapshotCase) {
        DinnerRecordDishSnapshotEntity snapshot = householdSnapshot();
        snapshotCase.mutation().accept(snapshot);
        stubDetail(snapshot);

        assertThatThrownBy(() -> service.detail(7L, 91L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Incomplete dinner record dish snapshot");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteSystemSnapshots")
    void incompleteExplicitSystemSnapshotFailsSafely(SnapshotCase snapshotCase) {
        DinnerRecordDishSnapshotEntity snapshot = systemSnapshot();
        snapshotCase.mutation().accept(snapshot);
        stubDetail(snapshot);

        assertThatThrownBy(() -> service.detail(7L, 91L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Incomplete dinner record dish snapshot");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopeLessRowsWithV7Data")
    void scopeLessRowIsLegacyOnlyWhenEveryV7FieldIsEmpty(SnapshotCase snapshotCase) {
        DinnerRecordDishSnapshotEntity snapshot = snapshot();
        snapshotCase.mutation().accept(snapshot);
        stubDetail(snapshot);

        assertThatThrownBy(() -> service.detail(7L, 91L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Incomplete dinner record dish snapshot");
    }

    @Test
    void explicitSystemSnapshotReturnsStoredIngredientsWithNullServings() {
        DinnerRecordDishSnapshotEntity snapshot = systemSnapshot();
        stubDetail(snapshot);

        var dish = service.detail(7L, 91L).dishes().getFirst();

        assertThat(dish.scope()).isEqualTo("SYSTEM");
        assertThat(dish.recipeVersion()).isEqualTo(1L);
        assertThat(dish.servings()).isNull();
        assertThat(dish.method()).isNull();
        assertThat(dish.ingredients()).singleElement()
                .satisfies(ingredient -> assertThat(ingredient.required()).isTrue());
    }

    @Test
    void malformedSnapshotJsonKeepsCodecFailureMessage() {
        DinnerRecordDishSnapshotEntity snapshot = householdSnapshot();
        snapshot.setIngredientsJson("{");
        stubDetail(snapshot);

        assertThatThrownBy(() -> service.detail(7L, 91L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid dinner record snapshot JSON");
    }

    private static Stream<String> inactiveMembershipStatuses() {
        return Stream.of("LEFT", "REMOVED");
    }

    private static Stream<String> unavailableHouseholds() {
        return Stream.of("missing", "inactive");
    }

    private static Stream<SnapshotCase> incompleteHouseholdSnapshots() {
        return Stream.of(
                new SnapshotCase("missing recipe version",
                        snapshot -> snapshot.setRecipeVersion(null)),
                new SnapshotCase("non-positive recipe version",
                        snapshot -> snapshot.setRecipeVersion(0L)),
                new SnapshotCase("missing servings",
                        snapshot -> snapshot.setServings(null)),
                new SnapshotCase("servings below publication range",
                        snapshot -> snapshot.setServings(0)),
                new SnapshotCase("servings above publication range",
                        snapshot -> snapshot.setServings(21)),
                new SnapshotCase("missing all method metadata", snapshot -> {
                    snapshot.setMethodId(null);
                    snapshot.setMethodName(null);
                    snapshot.setCookingStyle(null);
                }),
                new SnapshotCase("missing method steps",
                        snapshot -> snapshot.setMethodStepsJson("[]")),
                new SnapshotCase("more than twelve method steps",
                        snapshot -> snapshot.setMethodStepsJson(stepsJson(13))),
                new SnapshotCase("missing ingredients",
                        snapshot -> snapshot.setIngredientsJson("[]")),
                new SnapshotCase("ingredients without a required item",
                        snapshot -> snapshot.setIngredientsJson(
                                "[{\"ingredientId\":101,\"name\":\"鸡蛋\","
                                        + "\"quantity\":1,\"unit\":\"枚\","
                                        + "\"required\":false,\"sortOrder\":0}]")));
    }

    private static Stream<SnapshotCase> incompleteSystemSnapshots() {
        return Stream.of(
                new SnapshotCase("system recipe version is not one",
                        snapshot -> snapshot.setRecipeVersion(2L)),
                new SnapshotCase("system method metadata is present",
                        snapshot -> snapshot.setMethodId(21L)),
                new SnapshotCase("system method steps are present",
                        snapshot -> snapshot.setMethodStepsJson(
                                "[{\"instruction\":\"翻炒\",\"sortOrder\":0}]")),
                new SnapshotCase("system ingredients are missing",
                        snapshot -> snapshot.setIngredientsJson("[]")),
                new SnapshotCase("system ingredients have no required item",
                        snapshot -> snapshot.setIngredientsJson(
                                "[{\"ingredientId\":101,\"name\":\"盐\","
                                        + "\"quantity\":1,\"unit\":\"克\","
                                        + "\"required\":false,\"sortOrder\":0}]")),
                new SnapshotCase("scope is absent despite V7 fields",
                        snapshot -> snapshot.setRecipeScope(null)),
                new SnapshotCase("scope is unsupported",
                        snapshot -> snapshot.setRecipeScope("SHARED")));
    }

    private static Stream<SnapshotCase> scopeLessRowsWithV7Data() {
        return Stream.of(
                new SnapshotCase("scope-less row has recipe version",
                        snapshot -> snapshot.setRecipeVersion(1L)),
                new SnapshotCase("scope-less row has servings",
                        snapshot -> snapshot.setServings(2)),
                new SnapshotCase("scope-less row has method id",
                        snapshot -> snapshot.setMethodId(21L)),
                new SnapshotCase("scope-less row has method name",
                        snapshot -> snapshot.setMethodName("家常做法")),
                new SnapshotCase("scope-less row has cooking style",
                        snapshot -> snapshot.setCookingStyle("炒")),
                new SnapshotCase("scope-less row has method steps JSON",
                        snapshot -> snapshot.setMethodStepsJson("[]")),
                new SnapshotCase("scope-less row has ingredients JSON",
                        snapshot -> snapshot.setIngredientsJson("[]")));
    }

    private static String stepsJson(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> "{\"instruction\":\"步骤" + index
                        + "\",\"sortOrder\":" + index + "}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private LockedHouseholdContext stubContext(DinnerMenuEntity menu) {
        return stubContext(menu, access());
    }

    private LockedHouseholdContext stubContext(
            DinnerMenuEntity menu,
            ActiveHouseholdAccess access
    ) {
        LockedHouseholdContext context = mock(LockedHouseholdContext.class);
        when(context.access()).thenReturn(access);
        when(householdAccessService.lockActiveHouseholdContext(7L))
                .thenReturn(context);
        when(menuMapper.selectByHouseholdAndDateForUpdate(11L, LocalDate.of(2026, 7, 11)))
                .thenReturn(menu);
        return context;
    }

    private ActiveHouseholdAccess access() {
        return access(LocalDateTime.of(1970, 1, 1, 0, 0));
    }

    private ActiveHouseholdAccess access(LocalDateTime historyVisibleFrom) {
        return new ActiveHouseholdAccess(
                7L, 11L, 41L, 4L, "OWNER", historyVisibleFrom,
                8L, "Asia/Shanghai");
    }

    private DinnerMenuEntity menu(String status, Long version) {
        DinnerMenuEntity menu = new DinnerMenuEntity();
        menu.setId(31L);
        menu.setHouseholdId(11L);
        menu.setMenuDate(LocalDate.of(2026, 7, 11));
        menu.setStatus(status);
        menu.setVersion(version);
        return menu;
    }

    private DinnerCookingRecordEntity record(Long id, Long menuId) {
        DinnerCookingRecordEntity record = new DinnerCookingRecordEntity();
        record.setId(id);
        record.setHouseholdId(11L);
        record.setMenuId(menuId);
        record.setRecordDate(LocalDate.of(2026, 7, 11));
        record.setCompletedBy(7L);
        record.setCompletedAt(LocalDateTime.of(2026, 7, 11, 10, 0));
        return record;
    }

    private DinnerMenuSelectionEntity selection(Long userId, Long recipeId) {
        DinnerMenuSelectionEntity selection = new DinnerMenuSelectionEntity();
        selection.setMenuId(31L);
        selection.setUserId(userId);
        selection.setRecipeId(recipeId);
        return selection;
    }

    private DinnerRecordSnapshotAssembler.SnapshotDraft systemDraft(
            Long recipeId,
            Set<Long> selectors
    ) {
        return new DinnerRecordSnapshotAssembler.SnapshotDraft(
                recipeId, "SYSTEM", 1L, "系统菜", "/assets/recipes/" + recipeId + ".jpg",
                "家常菜", "鲜香", null, 10, selectors,
                null, null, null, List.of(),
                List.of(new RecordIngredientSnapshotResponse(
                        101L, "系统食材", BigDecimal.ONE, "个", true, 0)));
    }

    private DinnerRecordSnapshotAssembler.SnapshotDraft householdDraft(
            Long recipeId,
            Set<Long> selectors
    ) {
        return new DinnerRecordSnapshotAssembler.SnapshotDraft(
                recipeId, "HOUSEHOLD", 8L, "自家番茄炒蛋",
                "https://www.osheeep.com/media/recipes/family-list.webp",
                "家常菜", "鲜香", 2, 10, selectors,
                21L, "家常做法", "炒",
                List.of(
                        new RecordMethodStepSnapshotResponse("盛盘", 1),
                        new RecordMethodStepSnapshotResponse("翻炒", 0)),
                List.of(new RecordIngredientSnapshotResponse(
                        201L, "鸡蛋", BigDecimal.ONE, "枚", true, 0)));
    }

    private DinnerRecordSnapshotAssembler.SnapshotDraft householdDraftWithInvalidStep(
            Long recipeId,
            Set<Long> selectors
    ) {
        return new DinnerRecordSnapshotAssembler.SnapshotDraft(
                recipeId, "HOUSEHOLD", 8L, "自家番茄炒蛋",
                "https://www.osheeep.com/media/recipes/family-list.webp",
                "家常菜", "鲜香", 2, 10, selectors,
                21L, "家常做法", "炒",
                List.of(new RecordMethodStepSnapshotResponse("翻炒", -1)),
                List.of(new RecordIngredientSnapshotResponse(
                        201L, "鸡蛋", BigDecimal.ONE, "枚", true, 0)));
    }

    private DinnerRecordDishSnapshotEntity snapshot() {
        DinnerRecordDishSnapshotEntity snapshot = new DinnerRecordDishSnapshotEntity();
        snapshot.setRecipeId(14L);
        snapshot.setName("番茄炒蛋");
        snapshot.setCategory("家常菜");
        snapshot.setFlavor("酸甜");
        snapshot.setEstimatedMinutes(10);
        snapshot.setSelectedByUserIds("[7,8]");
        snapshot.setSortOrder(0);
        return snapshot;
    }

    private DinnerRecordDishSnapshotEntity householdSnapshot() {
        DinnerRecordDishSnapshotEntity snapshot = snapshot();
        snapshot.setRecipeScope("HOUSEHOLD");
        snapshot.setRecipeVersion(8L);
        snapshot.setServings(2);
        snapshot.setMethodId(21L);
        snapshot.setMethodName("家常做法");
        snapshot.setCookingStyle("炒");
        snapshot.setMethodStepsJson(
                "[{\"instruction\":\"盛盘\",\"sortOrder\":1},"
                        + "{\"instruction\":\"翻炒\",\"sortOrder\":0}]");
        snapshot.setIngredientsJson(
                "[{\"ingredientId\":101,\"name\":\"鸡蛋\",\"quantity\":null,"
                        + "\"unit\":\"枚\",\"required\":true,\"sortOrder\":0}]");
        return snapshot;
    }

    private DinnerRecordDishSnapshotEntity systemSnapshot() {
        DinnerRecordDishSnapshotEntity snapshot = snapshot();
        snapshot.setRecipeScope("SYSTEM");
        snapshot.setRecipeVersion(1L);
        snapshot.setServings(null);
        snapshot.setMethodStepsJson("[]");
        snapshot.setIngredientsJson(
                "[{\"ingredientId\":101,\"name\":\"番茄\",\"quantity\":2,"
                        + "\"unit\":\"个\",\"required\":true,\"sortOrder\":0}]");
        return snapshot;
    }

    private void stubDetail(DinnerRecordDishSnapshotEntity snapshot) {
        when(householdAccessService.requireActiveHousehold(7L)).thenReturn(access());
        DinnerCookingRecordEntity record = record(91L, 31L);
        when(recordMapper.selectById(91L)).thenReturn(record);
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot));
    }

    private TodayMenuResponse today(Long recordId) {
        return new TodayMenuResponse(
                31L, LocalDate.of(2026, 7, 11), "COMPLETED", 6L,
                2, 1, 1, List.of(1L, 2L), List.of(),
                7L, Instant.parse("2026-07-11T10:00:00Z"),
                7L, Instant.parse("2026-07-11T11:00:00Z"), recordId);
    }

    private record SnapshotCase(
            String name,
            Consumer<DinnerRecordDishSnapshotEntity> mutation
    ) {
        @Override
        public String toString() {
            return name;
        }
    }
}
