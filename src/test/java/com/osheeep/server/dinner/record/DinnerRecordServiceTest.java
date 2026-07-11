package com.osheeep.server.dinner.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.menu.BusinessDateResolver;
import com.osheeep.server.dinner.menu.DinnerMenuService;
import com.osheeep.server.dinner.menu.dto.TodayMenuResponse;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecordServiceTest {

    @Mock private DinnerHouseholdMapper householdMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerMenuMapper menuMapper;
    @Mock private DinnerMenuSelectionMapper selectionMapper;
    @Mock private DinnerMenuActionMapper actionMapper;
    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerCookingRecordMapper recordMapper;
    @Mock private DinnerRecordDishSnapshotMapper snapshotMapper;
    @Mock private DinnerMenuService menuService;

    private DinnerRecordService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T11:00:00Z"), ZoneOffset.UTC);
        service = new DinnerRecordService(
                householdMapper, memberMapper, menuMapper, selectionMapper, actionMapper,
                recipeMapper, recordMapper, snapshotMapper, menuService,
                new BusinessDateResolver(), clock);
    }

    @Test
    void repeatedCompleteReturnsTheExistingRecord() {
        stubContext(menu("CONFIRMED", 5L));
        when(recordMapper.selectOne(any())).thenReturn(record(91L, 31L));
        when(menuService.today(7L)).thenReturn(today(91L));

        var result = service.complete(7L, 4L, "00000000-0000-4000-8000-000000000011");

        assertThat(result.recordId()).isEqualTo(91L);
        verify(recordMapper, never()).insert(any(DinnerCookingRecordEntity.class));
        verify(snapshotMapper, never()).insert(any(DinnerRecordDishSnapshotEntity.class));
    }

    @Test
    void completeCreatesOneRecordAndDishSnapshots() {
        DinnerMenuEntity menu = menu("CONFIRMED", 5L);
        stubContext(menu);
        when(recordMapper.selectOne(any())).thenReturn(null);
        when(selectionMapper.selectList(any())).thenReturn(List.of(
                selection(7L, 1L), selection(8L, 1L), selection(7L, 2L)));
        when(recipeMapper.selectByIds(any())).thenReturn(List.of(
                recipe(1L, "番茄炒蛋"), recipe(2L, "小炒黄牛肉")));
        when(recordMapper.insert(any(DinnerCookingRecordEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerCookingRecordEntity>getArgument(0).setId(91L);
            return 1;
        });
        when(menuService.today(7L)).thenReturn(today(91L));

        var result = service.complete(7L, 5L, "00000000-0000-4000-8000-000000000012");

        assertThat(result.recordId()).isEqualTo(91L);
        assertThat(menu.getStatus()).isEqualTo("COMPLETED");
        assertThat(menu.getVersion()).isEqualTo(6L);
        verify(recordMapper).insert(any(DinnerCookingRecordEntity.class));
        verify(snapshotMapper, org.mockito.Mockito.times(2))
                .insert(any(DinnerRecordDishSnapshotEntity.class));
        verify(actionMapper).insert(any(DinnerMenuActionEntity.class));
    }

    @Test
    void listsRecordsForTheCurrentHousehold() {
        when(memberMapper.selectOne(any())).thenReturn(member());
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
    void recordDetailCalculatesSnapshotSourceForTheCurrentUser() {
        when(memberMapper.selectOne(any())).thenReturn(member());
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

        assertThat(result.dishes()).singleElement()
                .extracting(item -> item.source())
                .isEqualTo("BOTH");
    }

    private void stubContext(DinnerMenuEntity menu) {
        when(memberMapper.selectOne(any())).thenReturn(member());
        when(householdMapper.selectById(11L)).thenReturn(household());
        when(menuMapper.selectByHouseholdAndDateForUpdate(11L, LocalDate.of(2026, 7, 11)))
                .thenReturn(menu);
    }

    private DinnerHouseholdMemberEntity member() {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setHouseholdId(11L);
        member.setUserId(7L);
        return member;
    }

    private DinnerHouseholdEntity household() {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(11L);
        household.setTimezone("Asia/Shanghai");
        household.setStatus("ACTIVE");
        return household;
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
        record.setMenuId(menuId);
        return record;
    }

    private DinnerMenuSelectionEntity selection(Long userId, Long recipeId) {
        DinnerMenuSelectionEntity selection = new DinnerMenuSelectionEntity();
        selection.setMenuId(31L);
        selection.setUserId(userId);
        selection.setRecipeId(recipeId);
        return selection;
    }

    private DinnerRecipeEntity recipe(Long id, String name) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(id);
        recipe.setName(name);
        recipe.setImagePath("/assets/recipes/" + id + ".jpg");
        recipe.setCategory("家常菜");
        recipe.setFlavor("鲜香");
        recipe.setEstimatedMinutes(10);
        return recipe;
    }

    private TodayMenuResponse today(Long recordId) {
        return new TodayMenuResponse(
                31L, LocalDate.of(2026, 7, 11), "COMPLETED", 6L,
                2, 1, 1, List.of(1L, 2L), List.of(),
                7L, Instant.parse("2026-07-11T10:00:00Z"),
                7L, Instant.parse("2026-07-11T11:00:00Z"), recordId);
    }
}
