package com.osheeep.server.dinner.recipe;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.dinner.recipe.dto.RecipeResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DinnerRecipeService {

    private final DinnerRecipeMapper recipeMapper;

    public DinnerRecipeService(DinnerRecipeMapper recipeMapper) {
        this.recipeMapper = recipeMapper;
    }

    public List<RecipeResponse> listSystemRecipes() {
        return recipeMapper.selectList(Wrappers.<DinnerRecipeEntity>lambdaQuery()
                        .eq(DinnerRecipeEntity::getScope, "SYSTEM")
                        .eq(DinnerRecipeEntity::getStatus, "ACTIVE")
                        .orderByAsc(DinnerRecipeEntity::getId))
                .stream()
                .map(RecipeResponse::from)
                .toList();
    }
}
