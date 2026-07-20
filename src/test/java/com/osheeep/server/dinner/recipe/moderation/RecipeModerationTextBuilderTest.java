package com.osheeep.server.dinner.recipe.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.recipe.RecipePublishSnapshot;
import com.osheeep.server.dinner.recipe.RecipeValidationException;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecipeModerationTextBuilderTest {

    private RecipeModerationTextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new RecipeModerationTextBuilder();
    }

    @Test
    void buildsStableTextInUserVisibleOrder() {
        RecipePublishSnapshot snapshot = snapshotWithMethod(new RecipeMethodResponse(
                201L, " 家常炒 ", " 炒 ",
                List.of(
                        new RecipeMethodStepResponse(" 炒鸡蛋 ", 1),
                        new RecipeMethodStepResponse(" 切番茄 ", 0))));

        String text = builder.build(snapshot);

        assertThat(text).isEqualTo(
                "口味：酸甜\n做法：家常炒\n烹饪方式：炒\n1. 切番茄\n2. 炒鸡蛋");
    }

    @Test
    void buildsStableTextWhenMethodLabelsAreNullOrBlank() {
        RecipePublishSnapshot snapshot = snapshotWithMethod(new RecipeMethodResponse(
                201L, null, "  ",
                List.of(new RecipeMethodStepResponse("切番茄", 0))));

        assertThat(builder.build(snapshot))
                .isEqualTo("口味：酸甜\n做法：\n烹饪方式：\n1. 切番茄");
    }

    @Test
    void acceptsContentAtWechatTwoThousandFiveHundredCharacterLimit() {
        assertThatCode(() -> builder.requireWithinLimit("菜".repeat(2500)))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesContentBeyondWechatTwoThousandFiveHundredCharacterLimit() {
        assertThatThrownBy(() -> builder.requireWithinLimit("菜".repeat(2501)))
                .isInstanceOfSatisfying(RecipeValidationException.class, error -> {
                    assertThat(error.errorCode())
                            .isEqualTo(ErrorCode.DINNER_RECIPE_VALIDATION_FAILED);
                    assertThat(error.issues()).singleElement().satisfies(issue -> {
                        assertThat(issue.step()).isEqualTo("PREVIEW");
                        assertThat(issue.field()).isEqualTo("content");
                    });
                });
    }

    private RecipePublishSnapshot snapshotWithMethod(RecipeMethodResponse method) {
        return new RecipePublishSnapshot(
                101L, 7L, 70L, 4L,
                "番茄炒蛋", "家常菜", " 酸甜 ", 2, 15, 9L,
                List.of(), method, null);
    }
}
