package com.osheeep.server.dinner.recipe.moderation;

import com.osheeep.server.dinner.recipe.RecipePublishSnapshot;
import com.osheeep.server.dinner.recipe.RecipeValidationException;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeValidationIssue;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RecipeModerationTextBuilder {

    private static final int WECHAT_CONTENT_LIMIT = 2500;

    public String build(RecipePublishSnapshot snapshot) {
        StringBuilder content = new StringBuilder()
                .append("口味：").append(trimToEmpty(snapshot.flavor()))
                .append("\n做法：").append(trimToEmpty(snapshot.defaultMethod().name()))
                .append("\n烹饪方式：")
                .append(trimToEmpty(snapshot.defaultMethod().cookingStyle()));

        List<RecipeMethodStepResponse> steps = snapshot.defaultMethod().steps().stream()
                .sorted(Comparator.comparingInt(RecipeMethodStepResponse::sortOrder))
                .toList();
        for (int index = 0; index < steps.size(); index++) {
            content.append('\n')
                    .append(index + 1)
                    .append(". ")
                    .append(trimToEmpty(steps.get(index).instruction()));
        }
        String result = content.toString();
        requireWithinLimit(result);
        return result;
    }

    public void requireWithinLimit(String content) {
        if (content.length() > WECHAT_CONTENT_LIMIT) {
            throw new RecipeValidationException(List.of(new RecipeValidationIssue(
                    "PREVIEW", "content", "菜谱内容不能超过2500字")));
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
