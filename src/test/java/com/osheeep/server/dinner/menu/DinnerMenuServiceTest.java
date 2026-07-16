package com.osheeep.server.dinner.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.menu.dto.MenuDishResponse;
import com.osheeep.server.dinner.menu.entity.DinnerMenuActionEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuActionMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
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
class DinnerMenuServiceTest {

    @Mock private DinnerHouseholdMapper householdMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerMenuMapper menuMapper;
    @Mock private DinnerMenuSelectionMapper selectionMapper;
    @Mock private DinnerMenuActionMapper actionMapper;
    @Mock private DinnerRecipeMapper recipeMapper;

    private DinnerMenuService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T10:00:00Z"), ZoneOffset.UTC);
        service = new DinnerMenuService(
                householdMapper,
                memberMapper,
                menuMapper,
                selectionMapper,
                actionMapper,
                recipeMapper,
                new BusinessDateResolver(),
                clock);
    }

    @Test
    void todayMergesSelectionsRelativeToCurrentUser() {
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(householdMapper.selectById(11L)).thenReturn(household(11L));
        when(menuMapper.selectOne(any())).thenReturn(menu(31L));
        when(selectionMapper.selectList(any())).thenReturn(List.of(
                selection(31L, 7L, 1L),
                selection(31L, 7L, 2L),
                selection(31L, 8L, 2L),
                selection(31L, 8L, 3L)));
        when(recipeMapper.selectByIds(any())).thenReturn(List.of(
                recipe(1L, "小炒黄牛肉"),
                recipe(2L, "番茄炒蛋"),
                recipe(3L, "紫菜蛋花汤")));

        var result = service.today(7L);

        assertThat(result.dishes()).extracting(MenuDishResponse::source)
                .containsExactly("ME", "BOTH", "PARTNER");
        assertThat(result.mySelectionCount()).isEqualTo(2);
        assertThat(result.partnerSelectionCount()).isEqualTo(2);
        assertThat(result.consensusCount()).isEqualTo(1);
        assertThat(result.selectedRecipeIds()).containsExactly(1L, 2L);
    }

    @Test
    void todayCreatesTheBusinessDayDraftWhenMissing() {
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(householdMapper.selectById(11L)).thenReturn(household(11L));
        when(menuMapper.selectOne(any())).thenReturn(null);
        when(menuMapper.insert(any(DinnerMenuEntity.class))).thenAnswer(invocation -> {
            invocation.<DinnerMenuEntity>getArgument(0).setId(31L);
            return 1;
        });
        when(selectionMapper.selectList(any())).thenReturn(List.of());

        var result = service.today(7L);

        assertThat(result.menuDate()).isEqualTo(LocalDate.of(2026, 7, 11));
        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.version()).isZero();
        verify(menuMapper).insert(any(DinnerMenuEntity.class));
    }

    @Test
    void confirmedMenuReturnsToDraftWhenSelectionsChange() {
        DinnerMenuEntity menu = menu(31L);
        menu.setStatus("CONFIRMED");
        menu.setConfirmedBy(7L);
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(householdMapper.selectById(11L)).thenReturn(household(11L));
        when(menuMapper.selectByHouseholdAndDateForUpdate(11L, LocalDate.of(2026, 7, 11)))
                .thenReturn(menu);
        when(selectionMapper.selectList(any()))
                .thenReturn(List.of(selection(31L, 7L, 1L)))
                .thenReturn(List.of(selection(31L, 7L, 1L), selection(31L, 7L, 2L)));
        when(recipeMapper.selectByIds(any())).thenReturn(List.of(
                publishedSystemRecipe(1L, "小炒黄牛肉"),
                publishedSystemRecipe(2L, "番茄炒蛋")));

        var result = service.updateSelections(7L, List.of(1L, 2L), 4L);

        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.version()).isEqualTo(5L);
        assertThat(menu.getConfirmedBy()).isNull();
        verify(selectionMapper).delete(any());
    }

    @Test
    void staleVersionDoesNotReplaceSelections() {
        DinnerMenuEntity menu = menu(31L);
        menu.setVersion(6L);
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(householdMapper.selectById(11L)).thenReturn(household(11L));
        when(menuMapper.selectByHouseholdAndDateForUpdate(11L, LocalDate.of(2026, 7, 11)))
                .thenReturn(menu);

        assertThatThrownBy(() -> service.updateSelections(7L, List.of(1L), 5L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.DINNER_MENU_VERSION_CONFLICT));
        verify(selectionMapper, never()).delete(any());
    }

    @Test
    void confirmAdvancesNonEmptyDraftOnce() {
        DinnerMenuEntity menu = menu(31L);
        menu.setVersion(5L);
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(householdMapper.selectById(11L)).thenReturn(household(11L));
        when(menuMapper.selectByHouseholdAndDateForUpdate(11L, LocalDate.of(2026, 7, 11)))
                .thenReturn(menu);
        when(actionMapper.selectOne(any())).thenReturn(null);
        when(selectionMapper.selectList(any())).thenReturn(List.of(selection(31L, 7L, 1L)));
        when(recipeMapper.selectByIds(any())).thenReturn(List.of(
                publishedSystemRecipe(1L, "番茄炒蛋")));

        var result = service.confirm(7L, 5L, "00000000-0000-4000-8000-000000000001");

        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.version()).isEqualTo(6L);
        assertThat(result.confirmedBy()).isEqualTo(7L);
        verify(actionMapper).insert(any(DinnerMenuActionEntity.class));
    }

    @Test
    void confirmRejectsEmptyMenu() {
        DinnerMenuEntity menu = menu(31L);
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(householdMapper.selectById(11L)).thenReturn(household(11L));
        when(menuMapper.selectByHouseholdAndDateForUpdate(11L, LocalDate.of(2026, 7, 11)))
                .thenReturn(menu);
        when(actionMapper.selectOne(any())).thenReturn(null);
        when(selectionMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.confirm(
                7L, 4L, "00000000-0000-4000-8000-000000000002"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ErrorCode.DINNER_MENU_EMPTY));
    }

    @Test
    void repeatedConfirmKeyReturnsCurrentMenuWithoutMutation() {
        DinnerMenuEntity menu = menu(31L);
        menu.setStatus("CONFIRMED");
        menu.setVersion(6L);
        DinnerMenuActionEntity action = new DinnerMenuActionEntity();
        action.setIdempotencyKey("00000000-0000-4000-8000-000000000003");
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 7L));
        when(householdMapper.selectById(11L)).thenReturn(household(11L));
        when(menuMapper.selectByHouseholdAndDateForUpdate(11L, LocalDate.of(2026, 7, 11)))
                .thenReturn(menu);
        when(actionMapper.selectOne(any())).thenReturn(action);
        when(selectionMapper.selectList(any())).thenReturn(List.of(selection(31L, 7L, 1L)));
        when(recipeMapper.selectByIds(any())).thenReturn(List.of(
                publishedSystemRecipe(1L, "番茄炒蛋")));

        var result = service.confirm(7L, 5L, action.getIdempotencyKey());

        assertThat(result.version()).isEqualTo(6L);
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
        verify(actionMapper, never()).insert(any(DinnerMenuActionEntity.class));
    }

    private DinnerHouseholdMemberEntity member(Long householdId, Long userId) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setHouseholdId(householdId);
        member.setUserId(userId);
        return member;
    }

    private DinnerHouseholdEntity household(Long id) {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(id);
        household.setTimezone("Asia/Shanghai");
        return household;
    }

    private DinnerMenuEntity menu(Long id) {
        DinnerMenuEntity menu = new DinnerMenuEntity();
        menu.setId(id);
        menu.setHouseholdId(11L);
        menu.setMenuDate(LocalDate.of(2026, 7, 11));
        menu.setStatus("DRAFT");
        menu.setVersion(4L);
        return menu;
    }

    private DinnerMenuSelectionEntity selection(Long menuId, Long userId, Long recipeId) {
        DinnerMenuSelectionEntity selection = new DinnerMenuSelectionEntity();
        selection.setMenuId(menuId);
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

    private DinnerRecipeEntity publishedSystemRecipe(Long id, String name) {
        DinnerRecipeEntity recipe = recipe(id, name);
        recipe.setScope("SYSTEM");
        recipe.setStatus("PUBLISHED");
        return recipe;
    }
}
