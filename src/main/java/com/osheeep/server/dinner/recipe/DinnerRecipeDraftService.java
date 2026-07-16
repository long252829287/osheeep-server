package com.osheeep.server.dinner.recipe;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientInput;
import com.osheeep.server.dinner.recipe.dto.ReplaceRecipeIngredientsRequest;
import com.osheeep.server.dinner.recipe.dto.UpdateDefaultMethodRequest;
import com.osheeep.server.dinner.recipe.dto.UpdateRecipeBasicInfoRequest;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeIngredientEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class DinnerRecipeDraftService {

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeIngredientMapper recipeIngredientMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerRecipeMethodStepMapper stepMapper;
    private final DinnerIngredientMapper ingredientMapper;
    private final DinnerRecipeAuthorizer authorizer;
    private final DinnerRecipeQueryService queryService;

    public DinnerRecipeDraftService(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeIngredientMapper recipeIngredientMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerRecipeMethodStepMapper stepMapper,
            DinnerIngredientMapper ingredientMapper,
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipeQueryService queryService
    ) {
        this.recipeMapper = recipeMapper;
        this.recipeIngredientMapper = recipeIngredientMapper;
        this.methodMapper = methodMapper;
        this.stepMapper = stepMapper;
        this.ingredientMapper = ingredientMapper;
        this.authorizer = authorizer;
        this.queryService = queryService;
    }

    @Transactional
    public RecipeDraftResponse create(Long userId) {
        RecipeAccess access = authorizer.requireMembership(userId);
        DinnerRecipeEntity draft = new DinnerRecipeEntity();
        draft.setScope("HOUSEHOLD");
        draft.setHouseholdId(access.householdId());
        draft.setCreatorId(userId);
        draft.setLastModifiedBy(userId);
        draft.setStatus("DRAFT");
        draft.setVersion(1L);
        recipeMapper.insert(draft);
        return new RecipeDraftResponse(
                draft.getId(), draft.getStatus(), draft.getVersion(),
                draft.getName(), draft.getCategory(), draft.getFlavor(),
                draft.getServings(), draft.getEstimatedMinutes(),
                List.of(), null, null,
                List.of("BASIC", "INGREDIENTS", "METHOD", "IMAGE"), null);
    }

    @Transactional
    public RecipeDraftResponse updateBasicInfo(
            Long userId,
            Long recipeId,
            UpdateRecipeBasicInfoRequest request
    ) {
        DinnerRecipeEntity draft = lockOwnedDraft(userId, recipeId, request.version());
        draft.setName(normalize(request.name()));
        draft.setCategory(normalize(request.category()));
        draft.setFlavor(normalize(request.flavor()));
        draft.setServings(request.servings());
        draft.setEstimatedMinutes(request.estimatedMinutes());

        DinnerRecipeMethodEntity method = findDefaultMethod(recipeId);
        if (method != null) {
            method.setEstimatedMinutes(draft.getEstimatedMinutes());
            methodMapper.updateById(method);
        }
        advance(draft, userId);
        return queryService.detail(userId, recipeId);
    }

    @Transactional
    public RecipeDraftResponse replaceIngredients(
            Long userId,
            Long recipeId,
            ReplaceRecipeIngredientsRequest request
    ) {
        DinnerRecipeEntity draft = lockOwnedDraft(userId, recipeId, request.version());
        validateIngredients(request.ingredients(), draft.getHouseholdId());

        recipeIngredientMapper.delete(
                Wrappers.<DinnerRecipeIngredientEntity>lambdaQuery()
                        .eq(DinnerRecipeIngredientEntity::getRecipeId, recipeId));
        for (int index = 0; index < request.ingredients().size(); index++) {
            RecipeIngredientInput input = request.ingredients().get(index);
            DinnerRecipeIngredientEntity ingredient = new DinnerRecipeIngredientEntity();
            ingredient.setRecipeId(recipeId);
            ingredient.setIngredientId(input.ingredientId());
            ingredient.setQuantity(input.quantity());
            ingredient.setUnit(input.unit().strip());
            ingredient.setIsRequired(input.required());
            ingredient.setSortOrder(index);
            recipeIngredientMapper.insert(ingredient);
        }
        advance(draft, userId);
        return queryService.detail(userId, recipeId);
    }

    @Transactional
    public RecipeDraftResponse updateDefaultMethod(
            Long userId,
            Long recipeId,
            UpdateDefaultMethodRequest request
    ) {
        DinnerRecipeEntity draft = lockOwnedDraft(userId, recipeId, request.version());
        DinnerRecipeMethodEntity method = findDefaultMethod(recipeId);
        if (method == null) {
            method = new DinnerRecipeMethodEntity();
            method.setRecipeId(recipeId);
            method.setIsDefault(true);
            method.setStatus("ACTIVE");
            method.setSortOrder(0);
            method.setName(normalize(request.name()));
            method.setCookingStyle(normalize(request.cookingStyle()));
            method.setEstimatedMinutes(draft.getEstimatedMinutes());
            methodMapper.insert(method);
        } else {
            method.setName(normalize(request.name()));
            method.setCookingStyle(normalize(request.cookingStyle()));
            method.setEstimatedMinutes(draft.getEstimatedMinutes());
            method.setIsDefault(true);
            method.setStatus("ACTIVE");
            method.setSortOrder(0);
            methodMapper.updateById(method);
        }

        Long methodId = method.getId();
        stepMapper.delete(Wrappers.<DinnerRecipeMethodStepEntity>lambdaQuery()
                .eq(DinnerRecipeMethodStepEntity::getMethodId, methodId));
        for (int index = 0; index < request.steps().size(); index++) {
            DinnerRecipeMethodStepEntity step = new DinnerRecipeMethodStepEntity();
            step.setMethodId(methodId);
            step.setInstruction(normalizeStep(request.steps().get(index).instruction()));
            step.setSortOrder(index);
            stepMapper.insert(step);
        }
        advance(draft, userId);
        return queryService.detail(userId, recipeId);
    }

    private DinnerRecipeEntity lockOwnedDraft(
            Long userId,
            Long recipeId,
            long expectedVersion
    ) {
        DinnerRecipeEntity draft = recipeMapper.selectByIdForUpdate(recipeId);
        if (draft == null) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_NOT_FOUND);
        }
        if (!"DRAFT".equals(draft.getStatus())
                || !Objects.equals(userId, draft.getCreatorId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!Objects.equals(draft.getVersion(), expectedVersion)) {
            throw new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
        }
        return draft;
    }

    private void advance(DinnerRecipeEntity draft, Long userId) {
        draft.setVersion(draft.getVersion() + 1L);
        draft.setLastModifiedBy(userId);
        recipeMapper.updateById(draft);
    }

    private void validateIngredients(
            List<RecipeIngredientInput> ingredients,
            Long householdId
    ) {
        Set<Long> ingredientIds = new HashSet<>();
        for (RecipeIngredientInput input : ingredients) {
            if (!ingredientIds.add(input.ingredientId())) {
                throw invalidIngredient();
            }
        }
        for (RecipeIngredientInput input : ingredients) {
            DinnerIngredientEntity ingredient = ingredientMapper.selectById(input.ingredientId());
            boolean visible = ingredient != null
                    && "ACTIVE".equals(ingredient.getStatus())
                    && ("SYSTEM".equals(ingredient.getScope())
                    || Objects.equals(ingredient.getHouseholdId(), householdId));
            if (!visible) {
                throw invalidIngredient();
            }
        }
    }

    private DinnerRecipeMethodEntity findDefaultMethod(Long recipeId) {
        return methodMapper.selectOne(Wrappers.<DinnerRecipeMethodEntity>lambdaQuery()
                .eq(DinnerRecipeMethodEntity::getRecipeId, recipeId)
                .eq(DinnerRecipeMethodEntity::getIsDefault, true)
                .eq(DinnerRecipeMethodEntity::getStatus, "ACTIVE")
                .orderByAsc(DinnerRecipeMethodEntity::getSortOrder)
                .orderByAsc(DinnerRecipeMethodEntity::getId)
                .last("LIMIT 1"));
    }

    private BusinessException invalidIngredient() {
        return new BusinessException(ErrorCode.DINNER_INGREDIENT_INVALID);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private String normalizeStep(String value) {
        return value == null ? "" : value.strip();
    }
}
