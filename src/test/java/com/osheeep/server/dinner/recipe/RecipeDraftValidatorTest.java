package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeValidationIssue;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecipeDraftValidatorTest {

    private final RecipeDraftValidator validator = new RecipeDraftValidator();

    @Test
    void reportsStableStepFieldAndMessageIssuesForIncompleteDraft() {
        List<RecipeValidationIssue> issues = validator.validate(snapshot(
                "", null, null, null, null, List.of(), null, null));

        assertThat(issues).extracting(
                        RecipeValidationIssue::step,
                        RecipeValidationIssue::field,
                        RecipeValidationIssue::message)
                .containsExactly(
                        tuple("BASIC", "name", "请填写菜名"),
                        tuple("BASIC", "category", "请填写分类"),
                        tuple("BASIC", "flavor", "请填写口味"),
                        tuple("BASIC", "servings", "请填写份量"),
                        tuple("BASIC", "estimatedMinutes", "请填写预计耗时"),
                        tuple("INGREDIENTS", "ingredients", "至少添加一种必需食材"),
                        tuple("METHOD", "defaultMethod", "请填写默认做法"),
                        tuple("IMAGE", "imageAssetId", "请选择一张已审核真实图片"));
    }

    @Test
    void rejectsBlankStepsAndMoreThanTwelveSteps() {
        List<RecipeMethodStepResponse> steps = java.util.stream.IntStream.range(0, 13)
                .mapToObj(index -> new RecipeMethodStepResponse(
                        index == 0 ? " " : "步骤" + index, index))
                .toList();

        assertThat(validator.validate(snapshotWithSteps(steps)))
                .extracting(RecipeValidationIssue::step, RecipeValidationIssue::field)
                .containsExactly(
                        tuple("METHOD", "steps"),
                        tuple("METHOD", "steps[0]"));
    }

    @Test
    void requiresAtLeastOneRequiredIngredientAndValidUnits() {
        List<RecipeIngredientResponse> ingredients = List.of(
                new RecipeIngredientResponse(1L, "番茄", null, " ", false, 0));

        assertThat(validator.validate(snapshot(
                "番茄炒蛋", "家常菜", "咸鲜", 2, 15,
                ingredients,
                new RecipeMethodResponse(
                        201L, "家常做法", "炒",
                        List.of(new RecipeMethodStepResponse("炒熟", 0))),
                9L)))
                .extracting(RecipeValidationIssue::step, RecipeValidationIssue::field)
                .containsExactly(
                        tuple("INGREDIENTS", "ingredients"),
                        tuple("INGREDIENTS", "ingredients[0].unit"));
    }

    @Test
    void completeDraftHasNoPublishIssues() {
        assertThat(validator.validate(snapshot(
                "番茄炒蛋", "家常菜", "咸鲜", 2, 15,
                List.of(new RecipeIngredientResponse(
                        1L, "番茄", new BigDecimal("2.000"), "个", true, 0)),
                new RecipeMethodResponse(
                        201L, "家常做法", "炒",
                        List.of(new RecipeMethodStepResponse("炒熟", 0))),
                9L))).isEmpty();
    }

    @Test
    void rejectsOverlongMethodNameAndCookingStyleWithStableFields() {
        RecipeMethodResponse method = new RecipeMethodResponse(
                201L, "做法" + "x".repeat(39), "烹饪" + "x".repeat(31),
                List.of(new RecipeMethodStepResponse("炒熟", 0)));

        assertThat(validator.validate(snapshot(
                "番茄炒蛋", "家常菜", "咸鲜", 2, 15,
                List.of(new RecipeIngredientResponse(
                        1L, "番茄", BigDecimal.ONE, "个", true, 0)),
                method, 9L)))
                .extracting(RecipeValidationIssue::step, RecipeValidationIssue::field)
                .containsExactly(
                        tuple("METHOD", "name"),
                        tuple("METHOD", "cookingStyle"));
    }

    @Test
    void rejectsQuantityWithMoreThanNineIntegerDigitsEvenInExponentForm() {
        RecipePublishSnapshot snapshot = snapshot(
                "番茄炒蛋", "家常菜", "咸鲜", 2, 15,
                List.of(new RecipeIngredientResponse(
                        1L, "番茄", new BigDecimal("1E+10"), "克", true, 0)),
                new RecipeMethodResponse(
                        201L, "家常做法", "炒",
                        List.of(new RecipeMethodStepResponse("炒熟", 0))),
                9L);

        assertThat(validator.validate(snapshot))
                .extracting(RecipeValidationIssue::step, RecipeValidationIssue::field)
                .containsExactly(tuple("INGREDIENTS", "ingredients[0].quantity"));
    }

    private RecipePublishSnapshot snapshotWithSteps(List<RecipeMethodStepResponse> steps) {
        return snapshot(
                "番茄炒蛋", "家常菜", "咸鲜", 2, 15,
                List.of(new RecipeIngredientResponse(
                        1L, "番茄", null, "个", true, 0)),
                new RecipeMethodResponse(201L, "家常做法", "炒", steps), 9L);
    }

    private RecipePublishSnapshot snapshot(
            String name,
            String category,
            String flavor,
            Integer servings,
            Integer estimatedMinutes,
            List<RecipeIngredientResponse> ingredients,
            RecipeMethodResponse method,
            Long imageAssetId
    ) {
        return new RecipePublishSnapshot(
                101L, 7L, 70L, 4L, name, category, flavor, servings,
                estimatedMinutes, imageAssetId, ingredients, method, "");
    }
}
