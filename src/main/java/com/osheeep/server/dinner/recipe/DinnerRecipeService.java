package com.osheeep.server.dinner.recipe;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.recipe.RecipeMatchCalculator.Requirement;
import com.osheeep.server.dinner.recipe.RecipeMatchCalculator.Stock;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientRow;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DinnerRecipeService {

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeIngredientMapper recipeIngredientMapper;
    private final DinnerHouseholdInventoryMapper inventoryMapper;
    private final DinnerHouseholdMemberMapper memberMapper;
    private final RecipeMatchCalculator matchCalculator;

    @Autowired
    public DinnerRecipeService(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeIngredientMapper recipeIngredientMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerHouseholdMemberMapper memberMapper
    ) {
        this(recipeMapper, recipeIngredientMapper, inventoryMapper, memberMapper,
                new RecipeMatchCalculator());
    }

    DinnerRecipeService(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeIngredientMapper recipeIngredientMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerHouseholdMemberMapper memberMapper,
            RecipeMatchCalculator matchCalculator
    ) {
        this.recipeMapper = recipeMapper;
        this.recipeIngredientMapper = recipeIngredientMapper;
        this.inventoryMapper = inventoryMapper;
        this.memberMapper = memberMapper;
        this.matchCalculator = matchCalculator;
    }

    public List<RecipeResponse> discover(
            Long userId,
            Set<Long> includeIngredientIds,
            Set<Long> excludeIngredientIds,
            boolean onlyCookable
    ) {
        DinnerHouseholdMemberEntity membership = requireMembership(userId);
        List<DinnerRecipeEntity> recipes = recipeMapper.selectList(
                Wrappers.<DinnerRecipeEntity>lambdaQuery()
                        .eq(DinnerRecipeEntity::getScope, "SYSTEM")
                        .eq(DinnerRecipeEntity::getStatus, "ACTIVE")
                        .orderByAsc(DinnerRecipeEntity::getId));
        List<Long> recipeIds = recipes.stream().map(DinnerRecipeEntity::getId).toList();
        List<DinnerRecipeIngredientRow> ingredientRows = recipeIds.isEmpty()
                ? List.of()
                : recipeIngredientMapper.selectWithIngredientNames(recipeIds);
        List<DinnerHouseholdInventoryEntity> inventory = inventoryMapper.selectList(
                Wrappers.<DinnerHouseholdInventoryEntity>lambdaQuery()
                        .eq(DinnerHouseholdInventoryEntity::getHouseholdId,
                                membership.getHouseholdId()));

        Map<Long, List<DinnerRecipeIngredientRow>> rowsByRecipe = ingredientRows.stream()
                .collect(Collectors.groupingBy(DinnerRecipeIngredientRow::recipeId));
        Map<Long, Stock> householdStock = inventory.stream()
                .collect(Collectors.toMap(
                        DinnerHouseholdInventoryEntity::getIngredientId,
                        item -> new Stock(item.getQuantity(), item.getUnit())));

        return recipes.stream()
                .map(recipe -> response(
                        recipe,
                        rowsByRecipe.getOrDefault(recipe.getId(), List.of()),
                        householdStock,
                        includeIngredientIds,
                        excludeIngredientIds))
                .filter(response -> !onlyCookable || !"MISSING".equals(response.match().status()))
                .sorted(discoveryOrder())
                .toList();
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

    private RecipeResponse response(
            DinnerRecipeEntity recipe,
            List<DinnerRecipeIngredientRow> unsortedRows,
            Map<Long, Stock> householdStock,
            Set<Long> includeIngredientIds,
            Set<Long> excludeIngredientIds
    ) {
        List<DinnerRecipeIngredientRow> rows = unsortedRows.stream()
                .sorted(Comparator.comparingInt(DinnerRecipeIngredientRow::sortOrder))
                .toList();
        List<Requirement> requirements = rows.stream()
                .map(row -> new Requirement(
                        row.ingredientId(), row.name(), row.quantity(), row.unit(),
                        row.required(), row.sortOrder()))
                .toList();
        Map<Long, Stock> effectiveStock = new HashMap<>(householdStock);
        for (Requirement requirement : requirements) {
            if (includeIngredientIds.contains(requirement.ingredientId())) {
                effectiveStock.putIfAbsent(
                        requirement.ingredientId(), new Stock(null, requirement.unit()));
            }
        }
        excludeIngredientIds.forEach(effectiveStock::remove);

        List<RecipeIngredientResponse> ingredients = rows.stream()
                .map(row -> new RecipeIngredientResponse(
                        row.ingredientId(), row.name(), row.quantity(), row.unit(),
                        row.required(), row.sortOrder()))
                .toList();
        return new RecipeResponse(
                recipe.getId(), recipe.getName(), recipe.getImagePath(), recipe.getCategory(),
                recipe.getFlavor(), recipe.getEstimatedMinutes(), ingredients,
                matchCalculator.calculate(requirements, effectiveStock));
    }

    private Comparator<RecipeResponse> discoveryOrder() {
        return Comparator.comparingInt((RecipeResponse response) ->
                        statusRank(response.match().status()))
                .thenComparing(
                        response -> response.match().matchPercent(), Comparator.reverseOrder())
                .thenComparing(
                        RecipeResponse::estimatedMinutes,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RecipeResponse::id);
    }

    private static int statusRank(String status) {
        return switch (status) {
            case "AVAILABLE" -> 0;
            case "UNKNOWN_QUANTITY" -> 1;
            default -> 2;
        };
    }

    private DinnerHouseholdMemberEntity requireMembership(Long userId) {
        DinnerHouseholdMemberEntity membership = memberMapper.selectOne(
                Wrappers.<DinnerHouseholdMemberEntity>lambdaQuery()
                        .eq(DinnerHouseholdMemberEntity::getUserId, userId)
                        .last("LIMIT 1"));
        if (membership == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return membership;
    }
}
