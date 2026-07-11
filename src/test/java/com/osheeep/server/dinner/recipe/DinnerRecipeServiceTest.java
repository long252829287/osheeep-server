package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipeServiceTest {

    @Mock private DinnerRecipeMapper recipeMapper;

    @Test
    void listsActiveSystemRecipesInSeedOrder() {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(1L);
        recipe.setName("番茄炒蛋");
        recipe.setImagePath("/assets/recipes/tomato-eggs.jpg");
        recipe.setCategory("家常菜");
        recipe.setFlavor("酸甜");
        recipe.setEstimatedMinutes(10);
        when(recipeMapper.selectList(any())).thenReturn(List.of(recipe));

        var result = new DinnerRecipeService(recipeMapper).listSystemRecipes();

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(1L);
            assertThat(item.name()).isEqualTo("番茄炒蛋");
        });
    }
}
