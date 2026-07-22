package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodSummaryResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientRow;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipeServiceTest {

    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerHouseholdInventoryMapper inventoryMapper;
    @Mock private DinnerRecipeAuthorizer authorizer;
    @Mock private DinnerRecipeCatalogAssembler catalogAssembler;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerHouseholdMapper householdMapper;

    private DinnerRecipeService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, DinnerRecipeEntity.class);
        TableInfoHelper.initTableInfo(assistant, DinnerHouseholdInventoryEntity.class);
        service = new DinnerRecipeService(
                recipeMapper, inventoryMapper, authorizer, catalogAssembler,
                new RecipeMatchCalculator());
    }

    @Test
    void discoversFromCurrentHouseholdWithBatchedRowsAndExactOrdering() {
        List<DinnerRecipeEntity> recipes = List.of(
                systemRecipe(9L, "缺少九", 8),
                systemRecipe(4L, "未知四", 30),
                systemRecipe(6L, "缺少六", 10),
                systemRecipe(2L, "可做二", null),
                systemRecipe(5L, "可做五", 20),
                systemRecipe(1L, "可做一", 20),
                systemRecipe(3L, "未知三", 12));
        List<DinnerRecipeIngredientRow> rows = List.of(
                row(1L, 101L, "番茄", "2", "个", true, 2),
                row(1L, 102L, "鸡蛋", "3", "枚", true, 1),
                row(2L, 103L, "盐", null, "克", false, 1),
                row(3L, 104L, "牛肉", "200", "克", true, 1),
                row(4L, 105L, "鸡肉", "200", "克", true, 1),
                row(6L, 101L, "番茄", "2", "个", true, 1),
                row(6L, 106L, "土豆", "2", "个", true, 2),
                row(9L, 107L, "可乐", "330", "毫升", true, 1));
        when(authorizer.requireMembership(7L)).thenReturn(new RecipeAccess(7L, 70L));
        when(recipeMapper.selectList(any())).thenReturn(recipes);
        when(catalogAssembler.assemble(recipes)).thenReturn(catalog(recipes, rows));
        when(inventoryMapper.selectList(any())).thenReturn(List.of(
                stock(70L, 101L, "4", "个"),
                stock(70L, 102L, "3", "枚"),
                stock(70L, 104L, null, "克"),
                stock(70L, 105L, null, "克")));

        var result = service.discover(7L, Set.of(), Set.of(), false);

        assertThat(result).extracting(item -> item.id())
                .containsExactly(1L, 5L, 2L, 3L, 4L, 6L, 9L);
        assertThat(result).extracting(item -> item.match().status())
                .containsExactly(
                        "AVAILABLE", "AVAILABLE", "AVAILABLE", "UNKNOWN_QUANTITY",
                        "UNKNOWN_QUANTITY", "MISSING", "MISSING");
        assertThat(result).extracting(item -> item.match().matchPercent())
                .containsExactly(100, 100, 100, 100, 100, 50, 0);
        assertThat(result.getFirst().ingredients()).extracting(item -> item.name())
                .containsExactly("鸡蛋", "番茄");
        assertThat(result.getFirst().imagePath()).isEqualTo("/assets/recipes/1.jpg");
        assertThat(result.getFirst().category()).isEqualTo("家常菜");
        assertThat(result.getFirst().flavor()).isEqualTo("咸鲜");
        verify(authorizer).requireMembership(7L);
        verify(recipeMapper).selectList(any());
        verify(catalogAssembler).assemble(recipes);
        verify(inventoryMapper).selectList(any());
    }

    @Test
    void discoversCurrentHouseholdRecipesWithTheSameMatchingAndSorting() {
        DinnerRecipeEntity family = householdRecipe(14L, 70L, 8L, 10);
        DinnerRecipeEntity system = systemRecipe(1L, "系统番茄炒蛋", 15);
        DinnerRecipeEntity otherHousehold = householdRecipe(15L, 71L, 3L, 5);
        DinnerRecipeEntity currentDraft = householdRecipe(16L, 70L, 1L, 5);
        currentDraft.setStatus("DRAFT");
        DinnerRecipeEntity currentArchived = householdRecipe(17L, 70L, 4L, 5);
        currentArchived.setStatus("ARCHIVED");
        List<DinnerRecipeEntity> visible = List.of(family, system);
        when(authorizer.requireMembership(7L)).thenReturn(new RecipeAccess(7L, 70L));
        when(recipeMapper.selectList(any())).thenReturn(visible);
        when(catalogAssembler.assemble(visible)).thenReturn(Map.of(
                14L, new DinnerRecipeCatalogAssembler.CatalogEntry(
                        family,
                        "https://www.osheeep.com/media/recipes/family-list.webp",
                        List.of(new RecipeIngredientResponse(
                                101L, "鸡蛋", null, "枚", true, 0)),
                        new RecipeMethodSummaryResponse(21L, "家常做法", "炒")),
                1L, new DinnerRecipeCatalogAssembler.CatalogEntry(
                        system,
                        "/assets/recipes/1.jpg",
                        List.of(new RecipeIngredientResponse(
                                101L, "鸡蛋", null, "枚", true, 0)),
                        null)));
        when(inventoryMapper.selectList(any()))
                .thenReturn(List.of(stock(70L, 101L, null, "枚")));

        var result = service.discover(7L, Set.of(), Set.of(), false);

        assertThat(result).extracting(item -> item.id()).containsExactly(14L, 1L);
        assertThat(result).extracting(item -> item.id()).doesNotContain(
                otherHousehold.getId(), currentDraft.getId(), currentArchived.getId());
        assertThat(result.getFirst().scope()).isEqualTo("HOUSEHOLD");
        assertThat(result.getFirst().version()).isEqualTo(8L);
        assertThat(result.getFirst().defaultMethod().name()).isEqualTo("家常做法");
        assertThat(result).extracting(item -> item.match().status())
                .containsOnly("UNKNOWN_QUANTITY");

        ArgumentCaptor<Wrapper<DinnerRecipeEntity>> query = wrapperCaptor();
        verify(recipeMapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment())
                .contains("status =", "scope =", " OR ", "household_id =");
        assertThat(parameterValues(query.getValue()).values())
                .contains("PUBLISHED", "SYSTEM", "HOUSEHOLD", 70L);
    }

    @Test
    void omitsDamagedPublishedHouseholdAggregateReturnedByVisibilityQuery() {
        DinnerRecipeEntity family = householdRecipe(14L, 70L, 8L, 10);
        DinnerRecipeEntity system = systemRecipe(1L, "系统番茄炒蛋", 15);
        List<DinnerRecipeEntity> recipes = List.of(family, system);
        when(authorizer.requireMembership(7L)).thenReturn(new RecipeAccess(7L, 70L));
        when(recipeMapper.selectList(any())).thenReturn(recipes);
        when(catalogAssembler.assemble(recipes)).thenReturn(Map.of(
                1L, new DinnerRecipeCatalogAssembler.CatalogEntry(
                        system, system.getImagePath(), List.of(), null)));
        when(inventoryMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.discover(7L, Set.of(), Set.of(), false))
                .extracting(item -> item.id())
                .containsExactly(1L);
    }

    @Test
    void temporaryStockRespectsInventoryThenIncludeThenExcludePrecedence() {
        stubSingleRecipe(
                List.of(
                        row(1L, 101L, "番茄", "2", "个", true, 1),
                        row(1L, 102L, "鸡蛋", "3", "枚", true, 2)),
                List.of(stock(70L, 101L, "1", "个")));

        var included = service.discover(7L, Set.of(101L, 102L), Set.of(), false).getFirst();

        assertThat(included.match().status()).isEqualTo("MISSING");
        assertThat(included.match().missingIngredients()).containsExactly("番茄");
        assertThat(included.match().unknownQuantityIngredients()).containsExactly("鸡蛋");

        var excluded = service.discover(7L, Set.of(102L), Set.of(102L), false).getFirst();

        assertThat(excluded.match().status()).isEqualTo("MISSING");
        assertThat(excluded.match().missingIngredients()).containsExactly("番茄", "鸡蛋");
        assertThat(excluded.match().unknownQuantityIngredients()).isEmpty();
    }

    @Test
    void onlyCookableRemovesMissingButPreservesUnknownQuantity() {
        stubSingleRecipe(
                List.of(row(1L, 101L, "番茄", "2", "个", true, 1)),
                List.of());

        assertThat(service.discover(7L, Set.of(101L), Set.of(), true))
                .singleElement()
                .satisfies(item -> assertThat(item.match().status())
                        .isEqualTo("UNKNOWN_QUANTITY"));
        assertThat(service.discover(7L, Set.of(), Set.of(), true)).isEmpty();
    }

    @Test
    void emptyRecipeResultAvoidsAggregateQueriesAndStillReadsInventoryOnce() {
        when(authorizer.requireMembership(7L)).thenReturn(new RecipeAccess(7L, 70L));
        when(recipeMapper.selectList(any())).thenReturn(List.of());
        when(catalogAssembler.assemble(List.of())).thenReturn(Map.of());
        when(inventoryMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.discover(7L, Set.of(), Set.of(), false)).isEmpty();

        verify(catalogAssembler).assemble(List.of());
        verify(inventoryMapper).selectList(any());
    }

    @Test
    void rejectsUserWithoutCurrentActiveHouseholdBeforeDiscoveryQueries() {
        when(authorizer.requireMembership(7L))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> service.discover(7L, Set.of(), Set.of(), false))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(recipeMapper, catalogAssembler, inventoryMapper);
    }

    @ParameterizedTest(name = "{0} member cannot discover recipes from the old household")
    @ValueSource(strings = {"LEFT", "REMOVED"})
    void formerMemberCannotDiscoverOldHouseholdRecipes(String membershipStatus) {
        DinnerHouseholdMemberEntity membership = membership(7L, 70L, membershipStatus);
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(membership);
        lenient().when(householdMapper.selectById(70L))
                .thenReturn(household(70L, "ACTIVE"));

        DinnerRecipeService accessControlledService = serviceWithRealAuthorizer();

        assertThatThrownBy(() ->
                accessControlledService.discover(7L, Set.of(), Set.of(), false))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode().name())
                                .isEqualTo("DINNER_HOUSEHOLD_REQUIRED"));

        verifyNoInteractions(catalogAssembler, inventoryMapper);
    }

    @ParameterizedTest(name = "ACTIVE member cannot discover when household is {0}")
    @ValueSource(strings = {"MISSING", "DISSOLVED"})
    void activeMemberCannotDiscoverWithoutActiveHousehold(String householdStatus) {
        when(memberMapper.selectActiveByUserId(7L))
                .thenReturn(membership(7L, 70L, "ACTIVE"));
        if (!"MISSING".equals(householdStatus)) {
            when(householdMapper.selectById(70L))
                    .thenReturn(household(70L, householdStatus));
        }

        DinnerRecipeService accessControlledService = serviceWithRealAuthorizer();

        assertThatThrownBy(() ->
                accessControlledService.discover(7L, Set.of(), Set.of(), false))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode().name())
                                .isEqualTo("DINNER_HOUSEHOLD_REQUIRED"));

        verifyNoInteractions(catalogAssembler, inventoryMapper);
    }

    @Test
    void systemListUsesExpandedSystemIdentityWithoutHouseholdLookup() {
        DinnerRecipeEntity system = systemRecipe(1L, "番茄炒蛋", 10);
        when(recipeMapper.selectList(any())).thenReturn(List.of(system));

        var result = service.listSystemRecipes();

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.scope()).isEqualTo("SYSTEM");
            assertThat(item.version()).isEqualTo(1L);
            assertThat(item.defaultMethod()).isNull();
        });
        verifyNoInteractions(authorizer, catalogAssembler, inventoryMapper);
    }

    private void stubSingleRecipe(
            List<DinnerRecipeIngredientRow> requirements,
            List<DinnerHouseholdInventoryEntity> inventory
    ) {
        DinnerRecipeEntity recipe = systemRecipe(1L, "番茄炒蛋", 10);
        List<DinnerRecipeEntity> recipes = List.of(recipe);
        when(authorizer.requireMembership(7L)).thenReturn(new RecipeAccess(7L, 70L));
        when(recipeMapper.selectList(any())).thenReturn(recipes);
        when(catalogAssembler.assemble(recipes)).thenReturn(catalog(recipes, requirements));
        when(inventoryMapper.selectList(any())).thenReturn(inventory);
    }

    private DinnerRecipeService serviceWithRealAuthorizer() {
        return new DinnerRecipeService(
                recipeMapper,
                inventoryMapper,
                new DinnerRecipeAuthorizer(memberMapper, householdMapper, recipeMapper),
                catalogAssembler,
                new RecipeMatchCalculator());
    }

    private DinnerHouseholdMemberEntity membership(
            Long userId,
            Long householdId,
            String status
    ) {
        DinnerHouseholdMemberEntity membership = new DinnerHouseholdMemberEntity();
        membership.setId(11L);
        membership.setUserId(userId);
        membership.setHouseholdId(householdId);
        membership.setStatus(status);
        membership.setRole("MEMBER");
        membership.setVersion(1L);
        membership.setHistoryVisibleFrom(LocalDateTime.of(2026, 7, 1, 0, 0));
        return membership;
    }

    private DinnerHouseholdEntity household(Long id, String status) {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(id);
        household.setStatus(status);
        household.setVersion(1L);
        household.setTimezone("Asia/Shanghai");
        return household;
    }

    private Map<Long, DinnerRecipeCatalogAssembler.CatalogEntry> catalog(
            List<DinnerRecipeEntity> recipes,
            List<DinnerRecipeIngredientRow> rows
    ) {
        Map<Long, List<RecipeIngredientResponse>> ingredientsByRecipe = rows.stream()
                .sorted(java.util.Comparator.comparing(DinnerRecipeIngredientRow::recipeId)
                        .thenComparingInt(DinnerRecipeIngredientRow::sortOrder))
                .collect(Collectors.groupingBy(
                        DinnerRecipeIngredientRow::recipeId,
                        LinkedHashMap::new,
                        Collectors.mapping(row -> new RecipeIngredientResponse(
                                        row.ingredientId(), row.name(), row.quantity(), row.unit(),
                                        row.required(), row.sortOrder()),
                                Collectors.toList())));
        Map<Long, DinnerRecipeCatalogAssembler.CatalogEntry> entries = new LinkedHashMap<>();
        recipes.forEach(recipe -> entries.put(
                recipe.getId(),
                new DinnerRecipeCatalogAssembler.CatalogEntry(
                        recipe,
                        recipe.getImagePath(),
                        ingredientsByRecipe.getOrDefault(recipe.getId(), List.of()),
                        null)));
        return entries;
    }

    private DinnerRecipeEntity systemRecipe(Long id, String name, Integer minutes) {
        DinnerRecipeEntity recipe = baseRecipe(id, name, minutes);
        recipe.setScope("SYSTEM");
        recipe.setImagePath("/assets/recipes/" + id + ".jpg");
        recipe.setVersion(1L);
        return recipe;
    }

    private DinnerRecipeEntity householdRecipe(
            Long id,
            Long householdId,
            Long version,
            Integer minutes
    ) {
        DinnerRecipeEntity recipe = baseRecipe(id, "自家番茄炒蛋", minutes);
        recipe.setScope("HOUSEHOLD");
        recipe.setHouseholdId(householdId);
        recipe.setCreatorId(7L);
        recipe.setVersion(version);
        recipe.setServings(2);
        recipe.setImageAssetId(91L);
        return recipe;
    }

    private DinnerRecipeEntity baseRecipe(Long id, String name, Integer minutes) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(id);
        recipe.setName(name);
        recipe.setCategory("家常菜");
        recipe.setFlavor("咸鲜");
        recipe.setEstimatedMinutes(minutes);
        recipe.setStatus("PUBLISHED");
        return recipe;
    }

    private DinnerRecipeIngredientRow row(
            Long recipeId,
            Long ingredientId,
            String name,
            String quantity,
            String unit,
            boolean required,
            int sortOrder
    ) {
        return new DinnerRecipeIngredientRow(
                recipeId, ingredientId, name,
                quantity == null ? null : new BigDecimal(quantity), unit, required, sortOrder);
    }

    private DinnerHouseholdInventoryEntity stock(
            Long householdId,
            Long ingredientId,
            String quantity,
            String unit
    ) {
        DinnerHouseholdInventoryEntity stock = new DinnerHouseholdInventoryEntity();
        stock.setHouseholdId(householdId);
        stock.setIngredientId(ingredientId);
        stock.setQuantity(quantity == null ? null : new BigDecimal(quantity));
        stock.setUnit(unit);
        return stock;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private static Map<String, Object> parameterValues(Wrapper<?> wrapper) {
        assertThat(wrapper).isInstanceOf(AbstractWrapper.class);
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }
}
