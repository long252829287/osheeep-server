package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.recipe.dto.RecipeValidationIssue;
import java.util.List;

public class RecipeValidationException extends BusinessException {

    private final List<RecipeValidationIssue> issues;

    public RecipeValidationException(List<RecipeValidationIssue> issues) {
        super(ErrorCode.DINNER_RECIPE_VALIDATION_FAILED);
        this.issues = List.copyOf(issues);
    }

    public List<RecipeValidationIssue> issues() {
        return issues;
    }
}
