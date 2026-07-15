package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.recipe.dto.RecipeResponse;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dinner/recipes")
public class DinnerRecipeController {

    private final DinnerRecipeService recipeService;

    public DinnerRecipeController(DinnerRecipeService recipeService) {
        this.recipeService = recipeService;
    }

    @GetMapping
    public ApiResponse<List<RecipeResponse>> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(defaultValue = "") Set<Long> includeIngredientIds,
            @RequestParam(defaultValue = "") Set<Long> excludeIngredientIds,
            @RequestParam(defaultValue = "false") boolean onlyCookable
    ) {
        return ApiResponse.ok(recipeService.discover(
                currentUser.id(), includeIngredientIds, excludeIngredientIds, onlyCookable));
    }
}
