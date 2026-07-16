package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientRow;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipeServiceTest {

    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipeIngredientMapper recipeIngredientMapper;
    @Mock private DinnerHouseholdInventoryMapper inventoryMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;

    private DinnerRecipeService service;

    @BeforeEach
    void setUp() {
        service = new DinnerRecipeService(
                recipeMapper, recipeIngredientMapper, inventoryMapper, memberMapper,
                new RecipeMatchCalculator());
    }

    @Test
    void discoversFromCurrentHouseholdWithBatchedRowsAndExactOrdering() {
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 70L));
        when(recipeMapper.selectList(any())).thenReturn(List.of(
                recipe(9L, "缺少九", 8),
                recipe(4L, "未知四", 30),
                recipe(6L, "缺少六", 10),
                recipe(2L, "可做二", null),
                recipe(5L, "可做五", 20),
                recipe(1L, "可做一", 20),
                recipe(3L, "未知三", 12)));
        when(recipeIngredientMapper.selectWithIngredientNames(
                List.of(9L, 4L, 6L, 2L, 5L, 1L, 3L)))
                .thenReturn(List.of(
                        row(1L, 101L, "番茄", "2", "个", true, 2),
                        row(1L, 102L, "鸡蛋", "3", "枚", true, 1),
                        row(2L, 103L, "盐", null, "克", false, 1),
                        row(3L, 104L, "牛肉", "200", "克", true, 1),
                        row(4L, 105L, "鸡肉", "200", "克", true, 1),
                        row(6L, 101L, "番茄", "2", "个", true, 1),
                        row(6L, 106L, "土豆", "2", "个", true, 2),
                        row(9L, 107L, "可乐", "330", "毫升", true, 1)));
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
        verify(memberMapper).selectOne(any());
        verify(recipeMapper).selectList(any());
        verify(recipeIngredientMapper).selectWithIngredientNames(
                List.of(9L, 4L, 6L, 2L, 5L, 1L, 3L));
        verify(inventoryMapper).selectList(any());
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
                .satisfies(item -> assertThat(item.match().status()).isEqualTo("UNKNOWN_QUANTITY"));
        assertThat(service.discover(7L, Set.of(), Set.of(), true)).isEmpty();
    }

    @Test
    void emptyRecipeResultAvoidsInvalidIngredientBatchAndStillReadsInventoryOnce() {
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 70L));
        when(recipeMapper.selectList(any())).thenReturn(List.of());
        when(inventoryMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.discover(7L, Set.of(), Set.of(), false)).isEmpty();

        verify(recipeIngredientMapper, never()).selectWithIngredientNames(any());
        verify(inventoryMapper).selectList(any());
    }

    @Test
    void rejectsUserWithoutCurrentHouseholdBeforeDiscoveryQueries() {
        when(memberMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.discover(7L, Set.of(), Set.of(), false))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(recipeMapper, recipeIngredientMapper, inventoryMapper);
    }

    private void stubSingleRecipe(
            List<DinnerRecipeIngredientRow> requirements,
            List<DinnerHouseholdInventoryEntity> inventory
    ) {
        when(memberMapper.selectOne(any())).thenReturn(member(11L, 70L));
        when(recipeMapper.selectList(any())).thenReturn(List.of(recipe(1L, "番茄炒蛋", 10)));
        when(recipeIngredientMapper.selectWithIngredientNames(List.of(1L))).thenReturn(requirements);
        when(inventoryMapper.selectList(any())).thenReturn(inventory);
    }

    private DinnerRecipeEntity recipe(Long id, String name, Integer minutes) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(id);
        recipe.setScope("SYSTEM");
        recipe.setName(name);
        recipe.setImagePath("/assets/recipes/" + id + ".jpg");
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

    private DinnerHouseholdMemberEntity member(Long id, Long householdId) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setId(id);
        member.setHouseholdId(householdId);
        member.setUserId(7L);
        return member;
    }
}
