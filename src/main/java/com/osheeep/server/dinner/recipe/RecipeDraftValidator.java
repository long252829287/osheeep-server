package com.osheeep.server.dinner.recipe;

import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeValidationIssue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RecipeDraftValidator {

    private static final int MAX_NAME_LENGTH = 40;
    private static final int MAX_BASIC_LABEL_LENGTH = 16;
    private static final int MAX_INGREDIENTS = 50;
    private static final int MAX_UNIT_LENGTH = 16;
    private static final int MAX_COOKING_STYLE_LENGTH = 32;
    private static final int MAX_STEPS = 12;
    private static final int MAX_INSTRUCTION_LENGTH = 160;

    public List<RecipeValidationIssue> validate(RecipePublishSnapshot snapshot) {
        List<RecipeValidationIssue> issues = new ArrayList<>();
        validateBasic(snapshot, issues);
        validateIngredients(snapshot.ingredients(), issues);
        validateMethod(snapshot.defaultMethod(), issues);
        if (snapshot.imageAssetId() == null) {
            issues.add(issue("IMAGE", "imageAssetId", "请选择一张已审核真实图片"));
        }
        return List.copyOf(issues);
    }

    private void validateBasic(
            RecipePublishSnapshot snapshot,
            List<RecipeValidationIssue> issues
    ) {
        if (!hasTextWithin(snapshot.name(), MAX_NAME_LENGTH)) {
            issues.add(issue("BASIC", "name", "请填写菜名"));
        }
        if (!hasTextWithin(snapshot.category(), MAX_BASIC_LABEL_LENGTH)) {
            issues.add(issue("BASIC", "category", "请填写分类"));
        }
        if (!hasTextWithin(snapshot.flavor(), MAX_BASIC_LABEL_LENGTH)) {
            issues.add(issue("BASIC", "flavor", "请填写口味"));
        }
        if (snapshot.servings() == null
                || snapshot.servings() < 1
                || snapshot.servings() > 20) {
            issues.add(issue("BASIC", "servings", "请填写份量"));
        }
        if (snapshot.estimatedMinutes() == null
                || snapshot.estimatedMinutes() < 1
                || snapshot.estimatedMinutes() > 1440) {
            issues.add(issue("BASIC", "estimatedMinutes", "请填写预计耗时"));
        }
    }

    private void validateIngredients(
            List<RecipeIngredientResponse> ingredients,
            List<RecipeValidationIssue> issues
    ) {
        List<RecipeIngredientResponse> safeIngredients =
                ingredients == null ? List.of() : ingredients;
        boolean hasRequired = safeIngredients.stream().anyMatch(RecipeIngredientResponse::required);
        if (!hasRequired) {
            issues.add(issue("INGREDIENTS", "ingredients", "至少添加一种必需食材"));
        }
        if (safeIngredients.size() > MAX_INGREDIENTS) {
            issues.add(issue("INGREDIENTS", "ingredients", "食材不能超过50种"));
        }
        for (int index = 0; index < safeIngredients.size(); index++) {
            RecipeIngredientResponse ingredient = safeIngredients.get(index);
            if (ingredient.ingredientId() == null) {
                issues.add(issue(
                        "INGREDIENTS", "ingredients[" + index + "].ingredientId",
                        "请选择有效食材"));
            }
            if (!hasTextWithin(ingredient.unit(), MAX_UNIT_LENGTH)) {
                issues.add(issue(
                        "INGREDIENTS", "ingredients[" + index + "].unit",
                        "请填写食材单位"));
            }
            if (!validQuantity(ingredient.quantity())) {
                issues.add(issue(
                        "INGREDIENTS", "ingredients[" + index + "].quantity",
                        "食材数量格式不正确"));
            }
        }
    }

    private void validateMethod(
            RecipeMethodResponse method,
            List<RecipeValidationIssue> issues
    ) {
        if (method == null) {
            issues.add(issue("METHOD", "defaultMethod", "请填写默认做法"));
            return;
        }
        if (!hasTextWithin(method.name(), MAX_NAME_LENGTH)) {
            issues.add(issue("METHOD", "name", "请填写做法名称"));
        }
        if (!hasTextWithin(method.cookingStyle(), MAX_COOKING_STYLE_LENGTH)) {
            issues.add(issue("METHOD", "cookingStyle", "请填写烹饪方式"));
        }
        List<RecipeMethodStepResponse> steps = method.steps() == null
                ? List.of() : method.steps();
        if (steps.isEmpty()) {
            issues.add(issue("METHOD", "steps", "至少添加一个做法步骤"));
            return;
        }
        if (steps.size() > MAX_STEPS) {
            issues.add(issue("METHOD", "steps", "做法步骤不能超过12步"));
        }
        for (int index = 0; index < steps.size(); index++) {
            String instruction = steps.get(index).instruction();
            if (!hasTextWithin(instruction, MAX_INSTRUCTION_LENGTH)) {
                issues.add(issue(
                        "METHOD", "steps[" + index + "]", "请填写做法步骤"));
            }
        }
    }

    private boolean hasTextWithin(String value, int maxLength) {
        return StringUtils.hasText(value) && value.length() <= maxLength;
    }

    private boolean validQuantity(BigDecimal quantity) {
        return quantity == null
                || (quantity.signum() >= 0
                && quantity.scale() <= 3
                && Math.max(quantity.precision() - quantity.scale(), 0) <= 9);
    }

    private RecipeValidationIssue issue(String step, String field, String message) {
        return new RecipeValidationIssue(step, field, message);
    }
}
