package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.osheeep.server.dinner.recipe.RecipeMatchCalculator.Requirement;
import com.osheeep.server.dinner.recipe.RecipeMatchCalculator.Stock;
import com.osheeep.server.dinner.recipe.dto.RecipeMatchResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecipeMatchCalculatorTest {

    private final RecipeMatchCalculator calculator = new RecipeMatchCalculator();

    @Test
    void distinguishesAvailableUnknownAndMissingRequiredIngredients() {
        List<Requirement> requirements = List.of(
                new Requirement(1L, "番茄", new BigDecimal("2"), "个", true, 1),
                new Requirement(2L, "鸡蛋", new BigDecimal("3"), "枚", true, 2),
                new Requirement(3L, "葱", null, "根", false, 3));
        Map<Long, Stock> stock = Map.of(
                1L, new Stock(new BigDecimal("4"), "个"),
                2L, new Stock(null, "枚"));

        RecipeMatchResponse result = calculator.calculate(requirements, stock);

        assertThat(result.status()).isEqualTo("UNKNOWN_QUANTITY");
        assertThat(result.matchedRequired()).isEqualTo(2);
        assertThat(result.totalRequired()).isEqualTo(2);
        assertThat(result.matchPercent()).isEqualTo(100);
        assertThat(result.missingIngredients()).isEmpty();
        assertThat(result.unknownQuantityIngredients()).containsExactly("鸡蛋");
    }

    @Test
    void insufficientKnownQuantityIsMissing() {
        RecipeMatchResponse result = calculator.calculate(
                List.of(new Requirement(
                        1L, "土豆", new BigDecimal("3"), "个", true, 1)),
                Map.of(1L, new Stock(new BigDecimal("2"), "个")));

        assertThat(result.status()).isEqualTo("MISSING");
        assertThat(result.matchedRequired()).isZero();
        assertThat(result.totalRequired()).isEqualTo(1);
        assertThat(result.matchPercent()).isZero();
        assertThat(result.missingIngredients()).containsExactly("土豆");
    }

    @Test
    void absentStockIsMissing() {
        RecipeMatchResponse result = calculator.calculate(
                List.of(new Requirement(
                        1L, "豆腐", new BigDecimal("1"), "块", true, 1)),
                Map.of());

        assertThat(result.status()).isEqualTo("MISSING");
        assertThat(result.missingIngredients()).containsExactly("豆腐");
    }

    @Test
    void unitMismatchIsMissing() {
        RecipeMatchResponse result = calculator.calculate(
                List.of(new Requirement(
                        1L, "牛奶", new BigDecimal("250"), "毫升", true, 1)),
                Map.of(1L, new Stock(new BigDecimal("1"), "盒")));

        assertThat(result.status()).isEqualTo("MISSING");
        assertThat(result.missingIngredients()).containsExactly("牛奶");
    }

    @Test
    void optionalOnlyRequirementsAreAvailableAtOneHundredPercent() {
        RecipeMatchResponse result = calculator.calculate(
                List.of(new Requirement(
                        1L, "香菜", new BigDecimal("1"), "把", false, 1)),
                Map.of());

        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.matchedRequired()).isZero();
        assertThat(result.totalRequired()).isZero();
        assertThat(result.matchPercent()).isEqualTo(100);
        assertThat(result.missingIngredients()).isEmpty();
        assertThat(result.unknownQuantityIngredients()).isEmpty();
    }

    @Test
    void completeInventoryIsAvailable() {
        RecipeMatchResponse result = calculator.calculate(
                List.of(
                        new Requirement(1L, "盐", null, "克", true, 1),
                        new Requirement(
                                2L, "面粉", new BigDecimal("200"), "克", true, 2)),
                Map.of(
                        1L, new Stock(null, "克"),
                        2L, new Stock(new BigDecimal("200.0"), "克")));

        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.matchedRequired()).isEqualTo(2);
        assertThat(result.totalRequired()).isEqualTo(2);
        assertThat(result.matchPercent()).isEqualTo(100);
        assertThat(result.missingIngredients()).isEmpty();
        assertThat(result.unknownQuantityIngredients()).isEmpty();
    }

    @Test
    void missingTakesPrecedenceAndIssueListsFollowRequirementSortOrder() {
        RecipeMatchResponse result = calculator.calculate(
                List.of(
                        new Requirement(
                                3L, "高汤", new BigDecimal("500"), "毫升", true, 30),
                        new Requirement(
                                1L, "胡萝卜", new BigDecimal("2"), "根", true, 10),
                        new Requirement(
                                4L, "蘑菇", new BigDecimal("4"), "朵", true, 40),
                        new Requirement(
                                2L, "洋葱", new BigDecimal("1"), "个", true, 20)),
                Map.of(
                        2L, new Stock(null, "个"),
                        3L, new Stock(null, "毫升")));

        assertThat(result.status()).isEqualTo("MISSING");
        assertThat(result.matchedRequired()).isEqualTo(2);
        assertThat(result.totalRequired()).isEqualTo(4);
        assertThat(result.matchPercent()).isEqualTo(50);
        assertThat(result.missingIngredients()).containsExactly("胡萝卜", "蘑菇");
        assertThat(result.unknownQuantityIngredients()).containsExactly("洋葱", "高汤");
    }
}
