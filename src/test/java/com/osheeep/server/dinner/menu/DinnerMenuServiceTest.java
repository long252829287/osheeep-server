package com.osheeep.server.dinner.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService;
import com.osheeep.server.dinner.household.DinnerHouseholdAccessService.ActiveHouseholdAccess;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.menu.dto.MenuDishResponse;
import com.osheeep.server.dinner.menu.entity.DinnerMenuActionEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuEntity;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuActionMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuMapper;
import com.osheeep.server.dinner.menu.mapper.DinnerMenuSelectionMapper;
import com.osheeep.server.dinner.recipe.DinnerRecipeCatalogAssembler;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodSummaryResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerMenuServiceTest {

    @Mock private DinnerHouseholdAccessService householdAccessService;
    @Mock private DinnerMenuMapper menuMapper;
    @Mock private DinnerMenuSelectionMapper selectionMapper;
    @Mock private DinnerMenuActionMapper actionMapper;
    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipeMethodMapper methodMapper;
    @Mock private DinnerImageAssetService imageAssetService;
    @Mock private DinnerRecipeCatalogAssembler catalogAssembler;

    private DinnerMenuService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        Stream.of(
                        DinnerMenuEntity.class,
                        DinnerMenuSelectionEntity.class,
                        DinnerMenuActionEntity.class)
                .forEach(entity -> TableInfoHelper.initTableInfo(assistant, entity));
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T10:00:00Z"), ZoneOffset.UTC);
        service = new DinnerMenuService(
                householdAccessService,
                menuMapper,
                selectionMapper,
                actionMapper,
                recipeMapper,
                methodMapper,
                imageAssetService,
                catalogAssembler,
                new BusinessDateResolver(),
                clock);
    }

    @Test
    void updateSelectionsPersistsResolvedSystemAndHouseholdIdentities() {
        DinnerMenuEntity menu = menu(31L);
        DinnerRecipeEntity system = publishedSystemRecipe(1L, "小炒黄牛肉");
        DinnerRecipeEntity family = publishedHouseholdRecipe(14L, 11L, 8L, 91L);
        List<DinnerRecipeEntity> recipes = List.of(system, family);
        stubLockedContext(menu);
        when(recipeMapper.selectByIds(any())).thenReturn(recipes);
        when(catalogAssembler.assemble(recipes)).thenReturn(validCatalog(recipes));
        when(selectionMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        systemSelection(31L, 7L, 1L),
                        householdSelection(31L, 7L, 14L, 8L, 21L)));
        when(methodMapper.selectByIds(List.of(21L)))
                .thenReturn(List.of(method(21L, 14L, "家常做法", "炒")));
        when(imageAssetService.findApprovedByIds(List.of(91L)))
                .thenReturn(Map.of(91L, approvedImage(91L)));

        var result = service.updateSelections(7L, List.of(14L, 1L), 4L);

        ArgumentCaptor<DinnerMenuSelectionEntity> inserted =
                ArgumentCaptor.forClass(DinnerMenuSelectionEntity.class);
        verify(selectionMapper, times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues())
                .anySatisfy(row -> {
                    assertThat(row.getRecipeId()).isEqualTo(1L);
                    assertThat(row.getRecipeVersion()).isEqualTo(1L);
                    assertThat(row.getMethodId()).isNull();
                })
                .anySatisfy(row -> {
                    assertThat(row.getRecipeId()).isEqualTo(14L);
                    assertThat(row.getRecipeVersion()).isEqualTo(8L);
                    assertThat(row.getMethodId()).isEqualTo(21L);
                });
        assertThat(result.dishes()).extracting(MenuDishResponse::scope)
                .containsExactly("SYSTEM", "HOUSEHOLD");
        assertThat(result.version()).isEqualTo(5L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequestedRecipes")
    void invalidRequestedRecipeFailsBeforeSelectionWrites(InvalidRecipeFixture fixture) {
        stubLockedContext(menu(31L));
        when(recipeMapper.selectByIds(List.of(14L))).thenReturn(fixture.rows());

        assertDinnerRecipeInvalid(
                () -> service.updateSelections(7L, List.of(14L), 4L));

        verify(selectionMapper, never()).delete(any());
        verify(selectionMapper, never()).insert(any(DinnerMenuSelectionEntity.class));
        verifyNoInteractions(catalogAssembler);
    }

    @ParameterizedTest(name = "{0} membership cannot read its former household menu")
    @MethodSource("inactiveMembershipStatuses")
    void todayRejectsInactiveMembership(String membershipStatus) {
        when(householdAccessService.requireActiveHousehold(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThatThrownBy(() -> service.today(7L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThat(membershipStatus).isIn("LEFT", "REMOVED");
        verifyNoInteractions(menuMapper, selectionMapper, recipeMapper);
    }

    @ParameterizedTest(name = "ACTIVE membership rejects {0} household")
    @MethodSource("unavailableHouseholds")
    void todayRejectsActiveMembershipWithoutActiveHousehold(String reason) {
        when(householdAccessService.requireActiveHousehold(7L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThatThrownBy(() -> service.today(7L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        assertThat(reason).isNotBlank();
        verifyNoInteractions(menuMapper, selectionMapper, recipeMapper);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("damagedPublishedHouseholdAggregates")
    void damagedPublishedHouseholdAggregateFailsBeforeSelectionWrites(String reason) {
        DinnerRecipeEntity family = publishedHouseholdRecipe(14L, 11L, 8L, 91L);
        stubLockedContext(menu(31L));
        when(recipeMapper.selectByIds(List.of(14L))).thenReturn(List.of(family));
        when(catalogAssembler.assemble(List.of(family))).thenReturn(Map.of());

        assertDinnerRecipeInvalid(
                () -> service.updateSelections(7L, List.of(14L), 4L));

        assertThat(reason).isNotBlank();
        verify(selectionMapper, never()).delete(any());
        verify(selectionMapper, never()).insert(any(DinnerMenuSelectionEntity.class));
    }

    @Test
    void todayMergesTwoSelectorsForSameSavedFamilyIdentity() {
        DinnerMenuEntity menu = menu(31L);
        DinnerRecipeEntity family = publishedHouseholdRecipe(14L, 11L, 8L, 91L);
        stubTodayContext(menu);
        when(selectionMapper.selectList(any())).thenReturn(List.of(
                householdSelection(31L, 7L, 14L, 8L, 21L),
                householdSelection(31L, 8L, 14L, 8L, 21L)));
        when(recipeMapper.selectByIds(List.of(14L))).thenReturn(List.of(family));
        when(methodMapper.selectByIds(List.of(21L)))
                .thenReturn(List.of(method(21L, 14L, "家常做法", "炒")));
        when(imageAssetService.findApprovedByIds(List.of(91L)))
                .thenReturn(Map.of(91L, approvedImage(91L)));

        var result = service.today(7L);

        assertThat(result.dishes()).singleElement().satisfies(dish -> {
            assertThat(dish.source()).isEqualTo("BOTH");
            assertThat(dish.scope()).isEqualTo("HOUSEHOLD");
            assertThat(dish.recipeVersion()).isEqualTo(8L);
            assertThat(dish.method())
                    .isEqualTo(new RecipeMethodSummaryResponse(21L, "家常做法", "炒"));
        });
        assertThat(result.consensusCount()).isEqualTo(1);
        assertThat(result.historyVisible()).isTrue();
    }

    @Test
    void todayMasksCompletedMenuBeforeCurrentMembershipVisibilityWindow() {
        DinnerMenuEntity menu = menu(31L);
        menu.setStatus("COMPLETED");
        menu.setVersion(6L);
        menu.setConfirmedBy(8L);
        menu.setConfirmedAt(LocalDateTime.of(2026, 7, 11, 9, 30));
        menu.setCompletedBy(8L);
        menu.setCompletedAt(LocalDateTime.of(2026, 7, 11, 10, 0));
        when(householdAccessService.requireActiveHousehold(7L)).thenReturn(
                access(LocalDateTime.of(2026, 7, 11, 11, 0)));
        when(menuMapper.selectOne(any())).thenReturn(menu);

        var result = service.today(7L);

        assertThat(result.menuDate()).isEqualTo(LocalDate.of(2026, 7, 11));
        assertThat(result.status()).isEqualTo("PRE_MEMBERSHIP");
        assertThat(result.id()).isNull();
        assertThat(result.version()).isNull();
        assertThat(result.recordId()).isNull();
        assertThat(result.selectedRecipeIds()).isNullOrEmpty();
        assertThat(result.dishes()).isNullOrEmpty();
        assertThat(result.confirmedBy()).isNull();
        assertThat(result.completedBy()).isNull();
        assertThat(result.historyVisible()).isFalse();
        JsonNode serialized = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .valueToTree(result);
        List<String> serializedFields = new java.util.ArrayList<>();
        serialized.fieldNames().forEachRemaining(serializedFields::add);
        assertThat(serializedFields)
                .containsExactly("menuDate", "status", "historyVisible");
        assertThat(serialized.path("status").asText()).isEqualTo("PRE_MEMBERSHIP");
        assertThat(serialized.path("historyVisible").asBoolean()).isFalse();
        verifyNoInteractions(selectionMapper, recipeMapper, methodMapper, imageAssetService);
    }

    @Test
    void todayFailsClosedWhenCompletedMenuHasNoCompletionTime() {
        DinnerMenuEntity menu = menu(31L);
        menu.setStatus("COMPLETED");
        menu.setCompletedAt(null);
        when(householdAccessService.requireActiveHousehold(7L)).thenReturn(access());
        when(menuMapper.selectOne(any())).thenReturn(menu);

        var result = service.today(7L);

        assertThat(result.status()).isEqualTo("PRE_MEMBERSHIP");
        assertThat(result.historyVisible()).isFalse();
        verifyNoInteractions(selectionMapper, recipeMapper, methodMapper, imageAssetService);
    }

    @Test
    void todayKeepsCompletedMenuVisibleAtTheExactMembershipBoundary() {
        LocalDateTime visibleFrom = LocalDateTime.of(2026, 7, 11, 10, 0);
        DinnerMenuEntity menu = menu(31L);
        menu.setStatus("COMPLETED");
        menu.setCompletedAt(visibleFrom);
        when(householdAccessService.requireActiveHousehold(7L))
                .thenReturn(access(visibleFrom));
        when(menuMapper.selectOne(any())).thenReturn(menu);
        when(selectionMapper.selectList(any())).thenReturn(List.of());

        var result = service.today(7L);

        assertThat(result.id()).isEqualTo(31L);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.historyVisible()).isTrue();
    }

    @Test
    void confirmReplayMasksACompletedMenuBeforeTheMembershipBoundary() {
        DinnerMenuEntity menu = menu(31L);
        menu.setStatus("COMPLETED");
        menu.setCompletedAt(LocalDateTime.of(2026, 7, 11, 10, 0));
        when(householdAccessService.requireActiveHousehold(7L)).thenReturn(
                access(LocalDateTime.of(2026, 7, 11, 11, 0)));
        when(menuMapper.selectByHouseholdAndDateForUpdate(
                11L, LocalDate.of(2026, 7, 11))).thenReturn(menu);
        when(actionMapper.selectOne(any())).thenReturn(new DinnerMenuActionEntity());

        var result = service.confirm(
                7L, 6L, "00000000-0000-4000-8000-000000000099");

        assertThat(result.status()).isEqualTo("PRE_MEMBERSHIP");
        assertThat(result.id()).isNull();
        assertThat(result.recordId()).isNull();
        assertThat(result.historyVisible()).isFalse();
        verifyNoInteractions(selectionMapper, recipeMapper, methodMapper, imageAssetService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conflictingSelectionIdentities")
    void todayRejectsSelectorsWithConflictingSavedIdentity(
            String reason,
            List<DinnerMenuSelectionEntity> selections
    ) {
        stubTodayContext(menu(31L));
        when(selectionMapper.selectList(any())).thenReturn(selections);

        assertDinnerRecipeInvalid(() -> service.today(7L));

        assertThat(reason).isNotBlank();
        verifyNoInteractions(recipeMapper, methodMapper, imageAssetService);
    }

    @Test
    void todayRejectsSavedMethodBelongingToAnotherRecipe() {
        DinnerRecipeEntity family = publishedHouseholdRecipe(14L, 11L, 8L, 91L);
        stubTodayContext(menu(31L));
        when(selectionMapper.selectList(any())).thenReturn(List.of(
                householdSelection(31L, 7L, 14L, 8L, 21L)));
        when(recipeMapper.selectByIds(List.of(14L))).thenReturn(List.of(family));
        when(methodMapper.selectByIds(List.of(21L)))
                .thenReturn(List.of(method(21L, 15L, "错误做法", "炒")));
        when(imageAssetService.findApprovedByIds(List.of(91L)))
                .thenReturn(Map.of(91L, approvedImage(91L)));

        assertDinnerRecipeInvalid(() -> service.today(7L));
    }

    @Test
    void todayRejectsInactiveSavedMethod() {
        DinnerRecipeEntity family = publishedHouseholdRecipe(14L, 11L, 8L, 91L);
        DinnerRecipeMethodEntity inactiveMethod =
                method(21L, 14L, "旧做法", "炒");
        inactiveMethod.setStatus("INACTIVE");
        stubTodayContext(menu(31L));
        when(selectionMapper.selectList(any())).thenReturn(List.of(
                householdSelection(31L, 7L, 14L, 8L, 21L)));
        when(recipeMapper.selectByIds(List.of(14L))).thenReturn(List.of(family));
        when(methodMapper.selectByIds(List.of(21L))).thenReturn(List.of(inactiveMethod));
        when(imageAssetService.findApprovedByIds(List.of(91L)))
                .thenReturn(Map.of(91L, approvedImage(91L)));

        assertDinnerRecipeInvalid(() -> service.today(7L));

        verify(methodMapper).selectByIds(List.of(21L));
    }

    @Test
    void todayRejectsTamperedSelectionFromAnotherHousehold() {
        DinnerRecipeEntity foreign = publishedHouseholdRecipe(14L, 99L, 8L, 91L);
        stubTodayContext(menu(31L));
        when(selectionMapper.selectList(any())).thenReturn(List.of(
                householdSelection(31L, 7L, 14L, 8L, 21L)));
        when(recipeMapper.selectByIds(List.of(14L))).thenReturn(List.of(foreign));

        assertDinnerRecipeInvalid(() -> service.today(7L));
    }

    @Test
    void todayLoadsEachReferencedTableOnceForMultipleDishes() {
        DinnerRecipeEntity system = publishedSystemRecipe(1L, "小炒黄牛肉");
        DinnerRecipeEntity family = publishedHouseholdRecipe(14L, 11L, 8L, 91L);
        stubTodayContext(menu(31L));
        when(selectionMapper.selectList(any())).thenReturn(List.of(
                systemSelection(31L, 7L, 1L),
                householdSelection(31L, 8L, 14L, 8L, 21L)));
        when(recipeMapper.selectByIds(List.of(1L, 14L))).thenReturn(List.of(system, family));
        when(methodMapper.selectByIds(List.of(21L)))
                .thenReturn(List.of(method(21L, 14L, "家常做法", "炒")));
        when(imageAssetService.findApprovedByIds(List.of(91L)))
                .thenReturn(Map.of(91L, approvedImage(91L)));

        assertThat(service.today(7L).dishes()).hasSize(2);

        verify(recipeMapper).selectByIds(List.of(1L, 14L));
        verify(methodMapper).selectByIds(List.of(21L));
        verify(imageAssetService).findApprovedByIds(List.of(91L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("uniformlyCorruptedIdentities")
    void todayRejectsUniformlyCorruptedIdentity(
            String reason,
            DinnerMenuSelectionEntity selection,
            DinnerRecipeEntity recipe
    ) {
        stubTodayContext(menu(31L));
        when(selectionMapper.selectList(any())).thenReturn(List.of(selection));
        when(recipeMapper.selectByIds(List.of(recipe.getId()))).thenReturn(List.of(recipe));

        assertDinnerRecipeInvalid(() -> service.today(7L));

        assertThat(reason).isNotBlank();
    }

    @Test
    void todayCreatesTheBusinessDayDraftWhenMissing() {
        when(householdAccessService.requireActiveHousehold(7L)).thenReturn(access());
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
        DinnerRecipeEntity first = publishedSystemRecipe(1L, "小炒黄牛肉");
        DinnerRecipeEntity second = publishedSystemRecipe(2L, "番茄炒蛋");
        List<DinnerRecipeEntity> recipes = List.of(first, second);
        stubLockedContext(menu);
        when(selectionMapper.selectList(any()))
                .thenReturn(List.of(systemSelection(31L, 7L, 1L)))
                .thenReturn(List.of(
                        systemSelection(31L, 7L, 1L),
                        systemSelection(31L, 7L, 2L)));
        when(recipeMapper.selectByIds(any())).thenReturn(recipes);
        when(catalogAssembler.assemble(recipes)).thenReturn(validCatalog(recipes));

        var result = service.updateSelections(7L, List.of(1L, 2L), 4L);

        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.version()).isEqualTo(5L);
        assertThat(menu.getConfirmedBy()).isNull();
        verify(selectionMapper).delete(any());
    }

    @Test
    void staleVersionDoesNotResolveRecipesOrReplaceSelections() {
        DinnerMenuEntity menu = menu(31L);
        menu.setVersion(6L);
        stubLockedContext(menu);

        assertThatThrownBy(() -> service.updateSelections(7L, List.of(1L), 5L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_MENU_VERSION_CONFLICT));
        verify(selectionMapper, never()).delete(any());
        verify(selectionMapper, never()).insert(any(DinnerMenuSelectionEntity.class));
        verifyNoInteractions(recipeMapper, catalogAssembler);
    }

    @Test
    void confirmAdvancesNonEmptyDraftOnce() {
        DinnerMenuEntity menu = menu(31L);
        menu.setVersion(5L);
        stubLockedContext(menu);
        when(actionMapper.selectOne(any())).thenReturn(null);
        when(selectionMapper.selectList(any())).thenReturn(List.of(
                systemSelection(31L, 7L, 1L)));
        when(recipeMapper.selectByIds(List.of(1L))).thenReturn(List.of(
                publishedSystemRecipe(1L, "番茄炒蛋")));

        var result = service.confirm(7L, 5L, "00000000-0000-4000-8000-000000000001");

        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.version()).isEqualTo(6L);
        assertThat(result.confirmedBy()).isEqualTo(7L);
        verify(actionMapper).insert(any(DinnerMenuActionEntity.class));
    }

    @Test
    void confirmRejectsEmptyMenu() {
        stubLockedContext(menu(31L));
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
        stubLockedContext(menu);
        when(actionMapper.selectOne(any())).thenReturn(action);
        when(selectionMapper.selectList(any())).thenReturn(List.of(
                systemSelection(31L, 7L, 1L)));
        when(recipeMapper.selectByIds(List.of(1L))).thenReturn(List.of(
                publishedSystemRecipe(1L, "番茄炒蛋")));

        var result = service.confirm(7L, 5L, action.getIdempotencyKey());

        assertThat(result.version()).isEqualTo(6L);
        verify(menuMapper, never()).updateById(any(DinnerMenuEntity.class));
        verify(actionMapper, never()).insert(any(DinnerMenuActionEntity.class));
    }

    private static Stream<InvalidRecipeFixture> invalidRequestedRecipes() {
        DinnerRecipeEntity crossHousehold =
                publishedHouseholdRecipe(14L, 99L, 8L, 91L);
        DinnerRecipeEntity draft = publishedSystemRecipe(14L, "草稿系统菜");
        draft.setStatus("DRAFT");
        DinnerRecipeEntity archived = publishedSystemRecipe(14L, "归档系统菜");
        archived.setStatus("ARCHIVED");
        return Stream.of(
                new InvalidRecipeFixture("cross household", List.of(crossHousehold)),
                new InvalidRecipeFixture("draft", List.of(draft)),
                new InvalidRecipeFixture("archived", List.of(archived)),
                new InvalidRecipeFixture("missing", List.of()));
    }

    private static Stream<String> damagedPublishedHouseholdAggregates() {
        return Stream.of(
                "only optional ingredients",
                "blank method metadata",
                "zero steps",
                "missing approved image");
    }

    private static Stream<String> inactiveMembershipStatuses() {
        return Stream.of("LEFT", "REMOVED");
    }

    private static Stream<String> unavailableHouseholds() {
        return Stream.of("missing", "inactive");
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments>
            conflictingSelectionIdentities() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "different versions",
                        List.of(
                                householdSelection(31L, 7L, 14L, 8L, 21L),
                                householdSelection(31L, 8L, 14L, 9L, 21L))),
                org.junit.jupiter.params.provider.Arguments.of(
                        "different methods",
                        List.of(
                                householdSelection(31L, 7L, 14L, 8L, 21L),
                                householdSelection(31L, 8L, 14L, 8L, 22L))));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments>
            uniformlyCorruptedIdentities() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "household null method",
                        householdSelection(31L, 7L, 14L, 8L, null),
                        publishedHouseholdRecipe(14L, 11L, 8L, 91L)),
                org.junit.jupiter.params.provider.Arguments.of(
                        "system non-null method",
                        selection(31L, 7L, 1L, 1L, 21L),
                        publishedSystemRecipe(1L, "系统菜")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "system non-one version",
                        selection(31L, 7L, 1L, 2L, null),
                        publishedSystemRecipe(1L, "系统菜")));
    }

    private void stubLockedContext(DinnerMenuEntity menu) {
        when(householdAccessService.requireActiveHousehold(7L)).thenReturn(access());
        when(menuMapper.selectByHouseholdAndDateForUpdate(
                11L, LocalDate.of(2026, 7, 11))).thenReturn(menu);
    }

    private void stubTodayContext(DinnerMenuEntity menu) {
        when(householdAccessService.requireActiveHousehold(7L)).thenReturn(access());
        when(menuMapper.selectOne(any())).thenReturn(menu);
    }

    private static ActiveHouseholdAccess access() {
        return access(LocalDateTime.of(1970, 1, 1, 0, 0));
    }

    private static ActiveHouseholdAccess access(LocalDateTime historyVisibleFrom) {
        return new ActiveHouseholdAccess(
                7L, 11L, 41L, 4L, "OWNER", historyVisibleFrom,
                8L, "Asia/Shanghai");
    }

    private static DinnerMenuEntity menu(Long id) {
        DinnerMenuEntity menu = new DinnerMenuEntity();
        menu.setId(id);
        menu.setHouseholdId(11L);
        menu.setMenuDate(LocalDate.of(2026, 7, 11));
        menu.setStatus("DRAFT");
        menu.setVersion(4L);
        return menu;
    }

    private static DinnerMenuSelectionEntity systemSelection(
            Long menuId,
            Long userId,
            Long recipeId
    ) {
        return selection(menuId, userId, recipeId, 1L, null);
    }

    private static DinnerMenuSelectionEntity householdSelection(
            Long menuId,
            Long userId,
            Long recipeId,
            Long version,
            Long methodId
    ) {
        return selection(menuId, userId, recipeId, version, methodId);
    }

    private static DinnerMenuSelectionEntity selection(
            Long menuId,
            Long userId,
            Long recipeId,
            Long version,
            Long methodId
    ) {
        DinnerMenuSelectionEntity selection = new DinnerMenuSelectionEntity();
        selection.setMenuId(menuId);
        selection.setUserId(userId);
        selection.setRecipeId(recipeId);
        selection.setRecipeVersion(version);
        selection.setMethodId(methodId);
        return selection;
    }

    private static DinnerRecipeEntity publishedSystemRecipe(Long id, String name) {
        DinnerRecipeEntity recipe = baseRecipe(id, name);
        recipe.setScope("SYSTEM");
        recipe.setImagePath("/assets/recipes/" + id + ".jpg");
        recipe.setVersion(1L);
        return recipe;
    }

    private static DinnerRecipeEntity publishedHouseholdRecipe(
            Long id,
            Long householdId,
            Long version,
            Long imageAssetId
    ) {
        DinnerRecipeEntity recipe = baseRecipe(id, "自家番茄炒蛋");
        recipe.setScope("HOUSEHOLD");
        recipe.setHouseholdId(householdId);
        recipe.setVersion(version);
        recipe.setServings(2);
        recipe.setImageAssetId(imageAssetId);
        return recipe;
    }

    private static DinnerRecipeEntity baseRecipe(Long id, String name) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(id);
        recipe.setName(name);
        recipe.setCategory("家常菜");
        recipe.setFlavor("鲜香");
        recipe.setEstimatedMinutes(10);
        recipe.setStatus("PUBLISHED");
        return recipe;
    }

    private static DinnerRecipeMethodEntity method(
            Long id,
            Long recipeId,
            String name,
            String cookingStyle
    ) {
        DinnerRecipeMethodEntity method = new DinnerRecipeMethodEntity();
        method.setId(id);
        method.setRecipeId(recipeId);
        method.setName(name);
        method.setCookingStyle(cookingStyle);
        method.setStatus("ACTIVE");
        method.setIsDefault(true);
        return method;
    }

    private Map<Long, DinnerRecipeCatalogAssembler.CatalogEntry> validCatalog(
            List<DinnerRecipeEntity> recipes
    ) {
        Map<Long, DinnerRecipeCatalogAssembler.CatalogEntry> catalog = new LinkedHashMap<>();
        for (DinnerRecipeEntity recipe : recipes) {
            RecipeMethodSummaryResponse method = "HOUSEHOLD".equals(recipe.getScope())
                    ? new RecipeMethodSummaryResponse(21L, "家常做法", "炒")
                    : null;
            String imagePath = "HOUSEHOLD".equals(recipe.getScope())
                    ? approvedImage(recipe.getImageAssetId()).listUrl()
                    : recipe.getImagePath();
            catalog.put(recipe.getId(), new DinnerRecipeCatalogAssembler.CatalogEntry(
                    recipe,
                    imagePath,
                    List.of(new RecipeIngredientResponse(
                            101L, "鸡蛋", BigDecimal.ONE, "枚", true, 0)),
                    method));
        }
        return catalog;
    }

    private static ImageAssetResponse approvedImage(Long id) {
        return new ImageAssetResponse(
                id, "番茄炒蛋",
                "https://www.osheeep.com/media/recipes/family-list.webp",
                "https://www.osheeep.com/media/recipes/family-detail.webp",
                "https://example.com/source", "author", "CC BY 4.0",
                "https://creativecommons.org/licenses/by/4.0/",
                LocalDate.of(2026, 7, 1), 1200, 900);
    }

    private void assertDinnerRecipeInvalid(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable
    ) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(
                                ErrorCode.DINNER_RECIPE_INVALID));
    }

    private record InvalidRecipeFixture(
            String name,
            List<DinnerRecipeEntity> rows
    ) {
        @Override
        public String toString() {
            return name;
        }
    }
}
