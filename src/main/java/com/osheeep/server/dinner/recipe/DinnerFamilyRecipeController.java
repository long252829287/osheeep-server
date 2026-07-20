package com.osheeep.server.dinner.recipe;

import com.osheeep.server.common.api.ApiResponse;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeListItemResponse;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeTab;
import com.osheeep.server.dinner.recipe.dto.PublishRecipeRequest;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.dto.ReplaceRecipeIngredientsRequest;
import com.osheeep.server.dinner.recipe.dto.SelectRecipeImageRequest;
import com.osheeep.server.dinner.recipe.dto.UpdateDefaultMethodRequest;
import com.osheeep.server.dinner.recipe.dto.UpdateRecipeBasicInfoRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
@RequestMapping("/api/dinner/recipes")
public class DinnerFamilyRecipeController {

    private final DinnerRecipeDraftService draftService;
    private final DinnerRecipeQueryService queryService;
    private final DinnerRecipePublicationService publicationService;

    public DinnerFamilyRecipeController(
            DinnerRecipeDraftService draftService,
            DinnerRecipeQueryService queryService,
            DinnerRecipePublicationService publicationService
    ) {
        this.draftService = draftService;
        this.queryService = queryService;
        this.publicationService = publicationService;
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

    @PutMapping("/{id}/basic-info")
    public ApiResponse<RecipeDraftResponse> updateBasicInfo(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long id,
            @Valid @RequestBody UpdateRecipeBasicInfoRequest request
    ) {
        return ApiResponse.ok(draftService.updateBasicInfo(currentUser.id(), id, request));
    }

    @PutMapping("/{id}/ingredients")
    public ApiResponse<RecipeDraftResponse> replaceIngredients(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long id,
            @Valid @RequestBody ReplaceRecipeIngredientsRequest request
    ) {
        return ApiResponse.ok(draftService.replaceIngredients(currentUser.id(), id, request));
    }

    @PutMapping("/{id}/default-method")
    public ApiResponse<RecipeDraftResponse> updateDefaultMethod(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long id,
            @Valid @RequestBody UpdateDefaultMethodRequest request
    ) {
        return ApiResponse.ok(draftService.updateDefaultMethod(currentUser.id(), id, request));
    }

    @PutMapping("/{id}/image")
    public ApiResponse<RecipeDraftResponse> selectImage(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long id,
            @Valid @RequestBody SelectRecipeImageRequest request
    ) {
        return ApiResponse.ok(draftService.selectImage(currentUser.id(), id, request));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<RecipeDraftResponse> publish(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable Long id,
            @Valid @RequestBody PublishRecipeRequest request
    ) {
        return ApiResponse.ok(publicationService.publish(currentUser.id(), id, request.version()));
    }
}
