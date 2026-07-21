package com.osheeep.server.dinner.record;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientRow;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import com.osheeep.server.dinner.record.dto.RecordIngredientSnapshotResponse;
import com.osheeep.server.dinner.record.dto.RecordMethodStepSnapshotResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public final class DinnerRecordSnapshotAssembler {

    private static final int MAX_IMAGE_PATH_LENGTH = 255;
    private static final int MAX_STEPS = 12;
    private static final int MAX_INSTRUCTION_LENGTH = 160;

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeIngredientMapper ingredientMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerRecipeMethodStepMapper stepMapper;
    private final DinnerImageAssetService imageAssetService;

    public DinnerRecordSnapshotAssembler(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeIngredientMapper ingredientMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerRecipeMethodStepMapper stepMapper,
            DinnerImageAssetService imageAssetService
    ) {
        this.recipeMapper = recipeMapper;
        this.ingredientMapper = ingredientMapper;
        this.methodMapper = methodMapper;
        this.stepMapper = stepMapper;
        this.imageAssetService = imageAssetService;
    }

    public List<SnapshotDraft> assemble(
            Long householdId,
            List<DinnerMenuSelectionEntity> selections
    ) {
        if (householdId == null || selections == null || selections.isEmpty()) {
            throw invalidRecipe();
        }

        Map<Long, SelectionIdentity> identitiesByRecipe = new LinkedHashMap<>();
        Map<Long, Set<Long>> selectorsByRecipe = new LinkedHashMap<>();
        for (DinnerMenuSelectionEntity selection : selections) {
            if (selection == null
                    || selection.getRecipeId() == null
                    || selection.getUserId() == null) {
                throw invalidRecipe();
            }
            SelectionIdentity identity = new SelectionIdentity(
                    selection.getRecipeVersion(), selection.getMethodId());
            SelectionIdentity previous = identitiesByRecipe.putIfAbsent(
                    selection.getRecipeId(), identity);
            if (previous != null && !previous.equals(identity)) {
                throw invalidRecipe();
            }
            selectorsByRecipe.computeIfAbsent(
                            selection.getRecipeId(), ignored -> new TreeSet<>())
                    .add(selection.getUserId());
        }

        List<Long> recipeIds = identitiesByRecipe.keySet().stream().sorted().toList();
        Map<Long, DinnerRecipeEntity> recipesById = mapRecipes(
                recipeMapper.selectByIds(recipeIds), recipeIds);
        List<DinnerRecipeIngredientRow> ingredientRows =
                ingredientMapper.selectWithIngredientNames(recipeIds);

        List<Long> methodIds = identitiesByRecipe.values().stream()
                .map(SelectionIdentity::methodId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        Map<Long, DinnerRecipeMethodEntity> methodsById = methodIds.isEmpty()
                ? Map.of()
                : mapMethods(methodMapper.selectByIds(methodIds), methodIds);
        List<DinnerRecipeMethodStepEntity> stepRows = methodIds.isEmpty()
                ? List.of()
                : stepMapper.selectList(
                        Wrappers.<DinnerRecipeMethodStepEntity>lambdaQuery()
                                .in(DinnerRecipeMethodStepEntity::getMethodId, methodIds)
                                .orderByAsc(DinnerRecipeMethodStepEntity::getMethodId)
                                .orderByAsc(DinnerRecipeMethodStepEntity::getSortOrder)
                                .orderByAsc(DinnerRecipeMethodStepEntity::getId));

        List<Long> imageAssetIds = recipesById.values().stream()
                .filter(recipe -> "HOUSEHOLD".equals(recipe.getScope()))
                .map(DinnerRecipeEntity::getImageAssetId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        Map<Long, ImageAssetResponse> imagesById = imageAssetIds.isEmpty()
                ? Map.of()
                : imageAssetService.findApprovedByIds(imageAssetIds);

        Map<Long, List<RecordIngredientSnapshotResponse>> ingredientsByRecipe =
                mapIngredients(ingredientRows, new LinkedHashSet<>(recipeIds));
        Map<Long, List<RecordMethodStepSnapshotResponse>> stepsByMethod =
                mapSteps(stepRows, new LinkedHashSet<>(methodIds));
        if (!imagesById.keySet().equals(new LinkedHashSet<>(imageAssetIds))) {
            throw invalidRecipe();
        }

        List<SnapshotDraft> drafts = new ArrayList<>();
        for (Long recipeId : recipeIds) {
            DinnerRecipeEntity recipe = recipesById.get(recipeId);
            SelectionIdentity identity = identitiesByRecipe.get(recipeId);
            List<RecordIngredientSnapshotResponse> ingredients =
                    ingredientsByRecipe.getOrDefault(recipeId, List.of());
            validateBasics(recipe);
            if (ingredients.stream().noneMatch(
                    RecordIngredientSnapshotResponse::required)) {
                throw invalidRecipe();
            }

            if ("SYSTEM".equals(recipe.getScope())) {
                validateSystem(recipe, identity);
                drafts.add(new SnapshotDraft(
                        recipeId, "SYSTEM", 1L, recipe.getName(), recipe.getImagePath(),
                        recipe.getCategory(), recipe.getFlavor(), recipe.getServings(),
                        recipe.getEstimatedMinutes(), selectorsByRecipe.get(recipeId),
                        null, null, null, List.of(), ingredients));
                continue;
            }

            validateHousehold(recipe, identity, householdId);
            DinnerRecipeMethodEntity method = methodsById.get(identity.methodId());
            List<RecordMethodStepSnapshotResponse> steps =
                    stepsByMethod.getOrDefault(identity.methodId(), List.of());
            if (method == null
                    || !Objects.equals(method.getRecipeId(), recipeId)
                    || !"ACTIVE".equals(method.getStatus())
                    || !StringUtils.hasText(method.getName())
                    || !StringUtils.hasText(method.getCookingStyle())
                    || steps.isEmpty()
                    || steps.size() > MAX_STEPS) {
                throw invalidRecipe();
            }
            ImageAssetResponse image = imagesById.get(recipe.getImageAssetId());
            if (image == null
                    || !StringUtils.hasText(image.listUrl())
                    || image.listUrl().length() > MAX_IMAGE_PATH_LENGTH) {
                throw invalidRecipe();
            }
            drafts.add(new SnapshotDraft(
                    recipeId, "HOUSEHOLD", identity.recipeVersion(), recipe.getName(),
                    image.listUrl(), recipe.getCategory(), recipe.getFlavor(),
                    recipe.getServings(), recipe.getEstimatedMinutes(),
                    selectorsByRecipe.get(recipeId), method.getId(), method.getName(),
                    method.getCookingStyle(), steps, ingredients));
        }
        return List.copyOf(drafts);
    }

    private Map<Long, DinnerRecipeEntity> mapRecipes(
            List<DinnerRecipeEntity> rows,
            List<Long> expectedIds
    ) {
        Map<Long, DinnerRecipeEntity> byId = new HashMap<>();
        for (DinnerRecipeEntity row : rows) {
            if (row == null
                    || row.getId() == null
                    || byId.putIfAbsent(row.getId(), row) != null) {
                throw invalidRecipe();
            }
        }
        if (!byId.keySet().equals(new LinkedHashSet<>(expectedIds))) {
            throw invalidRecipe();
        }
        return byId;
    }

    private Map<Long, DinnerRecipeMethodEntity> mapMethods(
            List<DinnerRecipeMethodEntity> rows,
            List<Long> expectedIds
    ) {
        Map<Long, DinnerRecipeMethodEntity> byId = new HashMap<>();
        for (DinnerRecipeMethodEntity row : rows) {
            if (row == null
                    || row.getId() == null
                    || byId.putIfAbsent(row.getId(), row) != null) {
                throw invalidRecipe();
            }
        }
        if (!byId.keySet().equals(new LinkedHashSet<>(expectedIds))) {
            throw invalidRecipe();
        }
        return byId;
    }

    private Map<Long, List<RecordIngredientSnapshotResponse>> mapIngredients(
            List<DinnerRecipeIngredientRow> rows,
            Set<Long> expectedRecipeIds
    ) {
        Map<Long, Set<Long>> ingredientIdsByRecipe = new HashMap<>();
        Map<Long, List<RecordIngredientSnapshotResponse>> byRecipe = new HashMap<>();
        for (DinnerRecipeIngredientRow row : rows) {
            if (row == null
                    || row.recipeId() == null
                    || !expectedRecipeIds.contains(row.recipeId())
                    || row.ingredientId() == null
                    || !StringUtils.hasText(row.name())
                    || !StringUtils.hasText(row.unit())
                    || !validQuantity(row.quantity())
                    || !ingredientIdsByRecipe
                            .computeIfAbsent(row.recipeId(), ignored -> new HashSet<>())
                            .add(row.ingredientId())) {
                throw invalidRecipe();
            }
            byRecipe.computeIfAbsent(row.recipeId(), ignored -> new ArrayList<>())
                    .add(new RecordIngredientSnapshotResponse(
                            row.ingredientId(), row.name(), row.quantity(), row.unit(),
                            row.required(), row.sortOrder()));
        }
        byRecipe.replaceAll((ignored, values) -> values.stream()
                .sorted(Comparator.comparingInt(
                                RecordIngredientSnapshotResponse::sortOrder)
                        .thenComparing(RecordIngredientSnapshotResponse::ingredientId))
                .toList());
        return byRecipe;
    }

    private Map<Long, List<RecordMethodStepSnapshotResponse>> mapSteps(
            List<DinnerRecipeMethodStepEntity> rows,
            Set<Long> expectedMethodIds
    ) {
        Set<Long> stepIds = new HashSet<>();
        Map<Long, List<DinnerRecipeMethodStepEntity>> rawByMethod = new HashMap<>();
        for (DinnerRecipeMethodStepEntity row : rows) {
            if (row == null
                    || row.getId() == null
                    || !stepIds.add(row.getId())
                    || row.getMethodId() == null
                    || !expectedMethodIds.contains(row.getMethodId())
                    || !StringUtils.hasText(row.getInstruction())
                    || row.getInstruction().length() > MAX_INSTRUCTION_LENGTH) {
                throw invalidRecipe();
            }
            rawByMethod.computeIfAbsent(row.getMethodId(), ignored -> new ArrayList<>())
                    .add(row);
        }
        Map<Long, List<RecordMethodStepSnapshotResponse>> byMethod = new HashMap<>();
        rawByMethod.forEach((methodId, values) -> byMethod.put(
                methodId,
                values.stream()
                        .sorted(Comparator.comparingInt(
                                        DinnerRecipeMethodStepEntity::getSortOrder)
                                .thenComparing(DinnerRecipeMethodStepEntity::getId))
                        .map(step -> new RecordMethodStepSnapshotResponse(
                                step.getInstruction(), step.getSortOrder()))
                        .toList()));
        return byMethod;
    }

    private void validateBasics(DinnerRecipeEntity recipe) {
        if (!"PUBLISHED".equals(recipe.getStatus())
                || !StringUtils.hasText(recipe.getName())
                || !StringUtils.hasText(recipe.getCategory())
                || !StringUtils.hasText(recipe.getFlavor())
                || recipe.getEstimatedMinutes() == null) {
            throw invalidRecipe();
        }
    }

    private void validateSystem(
            DinnerRecipeEntity recipe,
            SelectionIdentity identity
    ) {
        if (!"SYSTEM".equals(recipe.getScope())
                || !Objects.equals(identity.recipeVersion(), 1L)
                || identity.methodId() != null
                || !StringUtils.hasText(recipe.getImagePath())
                || recipe.getImagePath().length() > MAX_IMAGE_PATH_LENGTH) {
            throw invalidRecipe();
        }
    }

    private void validateHousehold(
            DinnerRecipeEntity recipe,
            SelectionIdentity identity,
            Long householdId
    ) {
        if (!"HOUSEHOLD".equals(recipe.getScope())
                || !Objects.equals(recipe.getHouseholdId(), householdId)
                || identity.recipeVersion() == null
                || identity.recipeVersion() <= 0
                || !Objects.equals(recipe.getVersion(), identity.recipeVersion())
                || identity.methodId() == null
                || recipe.getImageAssetId() == null
                || recipe.getServings() == null
                || recipe.getServings() < 1
                || recipe.getServings() > 20) {
            throw invalidRecipe();
        }
    }

    private boolean validQuantity(BigDecimal quantity) {
        return quantity == null
                || (quantity.signum() >= 0
                && quantity.scale() <= 3
                && Math.max(quantity.precision() - quantity.scale(), 0) <= 9);
    }

    private BusinessException invalidRecipe() {
        return new BusinessException(ErrorCode.DINNER_RECIPE_INVALID);
    }

    public record SnapshotDraft(
            Long recipeId,
            String scope,
            Long recipeVersion,
            String name,
            String imagePath,
            String category,
            String flavor,
            Integer servings,
            Integer estimatedMinutes,
            Set<Long> selectedByUserIds,
            Long methodId,
            String methodName,
            String cookingStyle,
            List<RecordMethodStepSnapshotResponse> steps,
            List<RecordIngredientSnapshotResponse> ingredients
    ) {
        public SnapshotDraft {
            selectedByUserIds = Collections.unmodifiableSet(
                    new LinkedHashSet<>(new TreeSet<>(selectedByUserIds)));
            steps = List.copyOf(steps);
            ingredients = List.copyOf(ingredients);
        }
    }

    private record SelectionIdentity(Long recipeVersion, Long methodId) {
    }
}
