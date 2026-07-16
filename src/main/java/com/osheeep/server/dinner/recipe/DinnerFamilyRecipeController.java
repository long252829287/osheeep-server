package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeListItemResponse;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeTab;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dinner/recipes")
public class DinnerFamilyRecipeController {

    private final DinnerRecipeDraftService draftService;
    private final DinnerRecipeQueryService queryService;

    public DinnerFamilyRecipeController(
            DinnerRecipeDraftService draftService,
            DinnerRecipeQueryService queryService
    ) {
        this.draftService = draftService;
        this.queryService = queryService;
    }

    @PostMapping("/drafts")
    public ApiResponse<RecipeDraftResponse> createDraft(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ApiResponse.ok(draftService.create(currentUser.id()));
    }

    @GetMapping("/family")
    public ApiResponse<List<FamilyRecipeListItemResponse>> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam FamilyRecipeTab tab
    ) {
        return ApiResponse.ok(queryService.list(currentUser.id(), tab));
    }

    @GetMapping("/{id}")
    public ApiResponse<RecipeDraftResponse> detail(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long id
    ) {
        return ApiResponse.ok(queryService.detail(currentUser.id(), id));
    }
}
