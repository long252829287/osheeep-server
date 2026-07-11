package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dinner/recipes")
public class DinnerRecipeController {

    private final DinnerRecipeService recipeService;

    public DinnerRecipeController(DinnerRecipeService recipeService) {
        this.recipeService = recipeService;
    }

    @GetMapping
    public ApiResponse<List<RecipeResponse>> list() {
        return ApiResponse.ok(recipeService.listSystemRecipes());
    }
}
