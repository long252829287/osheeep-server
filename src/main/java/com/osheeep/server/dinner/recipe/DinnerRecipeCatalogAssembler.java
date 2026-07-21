package com.osheeep.server.dinner.recipe;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodSummaryResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeValidationIssue;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientRow;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public final class DinnerRecipeCatalogAssembler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DinnerRecipeCatalogAssembler.class);

    private final DinnerRecipeIngredientMapper ingredientMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerRecipeMethodStepMapper stepMapper;
    private final DinnerImageAssetService imageAssetService;
    private final RecipeDraftValidator validator;

    public DinnerRecipeCatalogAssembler(
            DinnerRecipeIngredientMapper ingredientMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerRecipeMethodStepMapper stepMapper,
            DinnerImageAssetService imageAssetService,
            RecipeDraftValidator validator
    ) {
        this.ingredientMapper = ingredientMapper;
        this.methodMapper = methodMapper;
        this.stepMapper = stepMapper;
        this.imageAssetService = imageAssetService;
        this.validator = validator;
    }

    public Map<Long, CatalogEntry> assemble(List<DinnerRecipeEntity> recipes) {
        if (recipes.isEmpty()) {
            return Map.of();
        }

        List<Long> recipeIds = recipes.stream()
                .map(DinnerRecipeEntity::getId)
                .distinct()
                .toList();
        Map<Long, List<RecipeIngredientResponse>> ingredientsByRecipe =
                loadIngredients(recipeIds);

        List<Long> householdRecipeIds = recipes.stream()
                .filter(recipe -> "HOUSEHOLD".equals(recipe.getScope()))
                .map(DinnerRecipeEntity::getId)
                .distinct()
                .toList();
        MethodData methodData = loadMethods(householdRecipeIds);
        Map<Long, ImageAssetResponse> imagesById = loadImages(recipes);

        Map<Long, CatalogEntry> entries = new LinkedHashMap<>();
        for (DinnerRecipeEntity recipe : recipes) {
            List<RecipeIngredientResponse> ingredients = List.copyOf(
                    ingredientsByRecipe.getOrDefault(recipe.getId(), List.of()));
            if ("SYSTEM".equals(recipe.getScope())) {
                entries.putIfAbsent(recipe.getId(), new CatalogEntry(
                        recipe, recipe.getImagePath(), ingredients, null));
                continue;
            }
            if (!"HOUSEHOLD".equals(recipe.getScope())
                    || !"PUBLISHED".equals(recipe.getStatus())) {
                continue;
            }
            CatalogEntry entry = householdEntry(
                    recipe, ingredients, methodData, imagesById);
            if (entry != null) {
                entries.putIfAbsent(recipe.getId(), entry);
            }
        }
        return Map.copyOf(entries);
    }

    private Map<Long, List<RecipeIngredientResponse>> loadIngredients(List<Long> recipeIds) {
        return ingredientMapper.selectWithIngredientNames(recipeIds).stream()
                .sorted(Comparator.comparing(DinnerRecipeIngredientRow::recipeId)
                        .thenComparingInt(DinnerRecipeIngredientRow::sortOrder))
                .collect(Collectors.groupingBy(
                        DinnerRecipeIngredientRow::recipeId,
                        LinkedHashMap::new,
                        Collectors.mapping(row -> new RecipeIngredientResponse(
                                        row.ingredientId(), row.name(), row.quantity(), row.unit(),
                                        row.required(), row.sortOrder()),
                                Collectors.toList())));
    }

    private MethodData loadMethods(List<Long> householdRecipeIds) {
        if (householdRecipeIds.isEmpty()) {
            return new MethodData(Map.of(), Set.of(), Map.of());
        }
        List<DinnerRecipeMethodEntity> methods = methodMapper.selectList(
                Wrappers.<DinnerRecipeMethodEntity>lambdaQuery()
                        .in(DinnerRecipeMethodEntity::getRecipeId, householdRecipeIds)
                        .eq(DinnerRecipeMethodEntity::getIsDefault, true)
                        .eq(DinnerRecipeMethodEntity::getStatus, "ACTIVE")
                        .orderByAsc(DinnerRecipeMethodEntity::getRecipeId)
                        .orderByAsc(DinnerRecipeMethodEntity::getSortOrder)
                        .orderByAsc(DinnerRecipeMethodEntity::getId));

        Map<Long, DinnerRecipeMethodEntity> methodsByRecipe = new LinkedHashMap<>();
        Set<Long> duplicateRecipeIds = new HashSet<>();
        for (DinnerRecipeMethodEntity method : methods) {
            DinnerRecipeMethodEntity previous =
                    methodsByRecipe.putIfAbsent(method.getRecipeId(), method);
            if (previous != null) {
                duplicateRecipeIds.add(method.getRecipeId());
            }
        }

        if (methods.isEmpty()) {
            return new MethodData(methodsByRecipe, duplicateRecipeIds, Map.of());
        }
        List<Long> methodIds = methods.stream()
                .map(DinnerRecipeMethodEntity::getId)
                .distinct()
                .toList();
        Map<Long, List<RecipeMethodStepResponse>> stepsByMethod = stepMapper.selectList(
                        Wrappers.<DinnerRecipeMethodStepEntity>lambdaQuery()
                                .in(DinnerRecipeMethodStepEntity::getMethodId, methodIds)
                                .orderByAsc(DinnerRecipeMethodStepEntity::getMethodId)
                                .orderByAsc(DinnerRecipeMethodStepEntity::getSortOrder)
                                .orderByAsc(DinnerRecipeMethodStepEntity::getId))
                .stream()
                .sorted(Comparator.comparing(DinnerRecipeMethodStepEntity::getMethodId)
                        .thenComparingInt(DinnerRecipeMethodStepEntity::getSortOrder))
                .collect(Collectors.groupingBy(
                        DinnerRecipeMethodStepEntity::getMethodId,
                        LinkedHashMap::new,
                        Collectors.mapping(step -> new RecipeMethodStepResponse(
                                        step.getInstruction(), step.getSortOrder()),
                                Collectors.toList())));
        return new MethodData(methodsByRecipe, duplicateRecipeIds, stepsByMethod);
    }

    private Map<Long, ImageAssetResponse> loadImages(List<DinnerRecipeEntity> recipes) {
        List<Long> imageIds = recipes.stream()
                .filter(recipe -> "HOUSEHOLD".equals(recipe.getScope()))
                .map(DinnerRecipeEntity::getImageAssetId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return imageIds.isEmpty() ? Map.of() : imageAssetService.findApprovedByIds(imageIds);
    }

    private CatalogEntry householdEntry(
            DinnerRecipeEntity recipe,
            List<RecipeIngredientResponse> ingredients,
            MethodData methodData,
            Map<Long, ImageAssetResponse> imagesById
    ) {
        DinnerRecipeMethodEntity method = methodData.methodsByRecipe().get(recipe.getId());
        RecipeMethodResponse methodResponse = method == null ? null : new RecipeMethodResponse(
                method.getId(), method.getName(), method.getCookingStyle(),
                List.copyOf(methodData.stepsByMethod().getOrDefault(method.getId(), List.of())));
        RecipePublishSnapshot snapshot = new RecipePublishSnapshot(
                recipe.getId(), recipe.getCreatorId(), recipe.getHouseholdId(),
                recipe.getVersion() == null ? 0L : recipe.getVersion(),
                recipe.getName(), recipe.getCategory(), recipe.getFlavor(), recipe.getServings(),
                recipe.getEstimatedMinutes(), recipe.getImageAssetId(), ingredients,
                methodResponse, "");
        List<RecipeValidationIssue> issues = new ArrayList<>(validator.validate(snapshot));
        if (methodData.duplicateRecipeIds().contains(recipe.getId())) {
            issues.add(new RecipeValidationIssue("METHOD", "defaultMethod", "默认做法不唯一"));
        }
        ImageAssetResponse image = recipe.getImageAssetId() == null
                ? null : imagesById.get(recipe.getImageAssetId());
        boolean invalidImage = image == null || !StringUtils.hasText(image.listUrl());
        if (!issues.isEmpty() || invalidImage) {
            List<String> issueFields = issues.stream()
                    .map(RecipeValidationIssue::field)
                    .distinct()
                    .toList();
            if (invalidImage && !issueFields.contains("imageAssetId")) {
                issueFields = new ArrayList<>(issueFields);
                issueFields.add("imageAssetId");
                issueFields = List.copyOf(issueFields);
            }
            LOGGER.warn(
                    "Omitting invalid household recipe catalog entry recipeId={}, householdId={}, issueFields={}",
                    recipe.getId(), recipe.getHouseholdId(), issueFields);
            return null;
        }
        return new CatalogEntry(
                recipe,
                image.listUrl(),
                ingredients,
                new RecipeMethodSummaryResponse(
                        method.getId(), method.getName(), method.getCookingStyle()));
    }

    public record CatalogEntry(
            DinnerRecipeEntity recipe,
            String imagePath,
            List<RecipeIngredientResponse> ingredients,
            RecipeMethodSummaryResponse defaultMethod
    ) {
        public CatalogEntry {
            ingredients = List.copyOf(ingredients);
        }
    }

    private record MethodData(
            Map<Long, DinnerRecipeMethodEntity> methodsByRecipe,
            Set<Long> duplicateRecipeIds,
            Map<Long, List<RecipeMethodStepResponse>> stepsByMethod
    ) {
    }
}
