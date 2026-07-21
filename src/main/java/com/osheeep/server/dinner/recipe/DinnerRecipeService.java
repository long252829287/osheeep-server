package com.osheeep.server.dinner.recipe;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.dinner.ingredient.entity.DinnerHouseholdInventoryEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerHouseholdInventoryMapper;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.DinnerRecipeCatalogAssembler.CatalogEntry;
import com.osheeep.server.dinner.recipe.RecipeMatchCalculator.Requirement;
import com.osheeep.server.dinner.recipe.RecipeMatchCalculator.Stock;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
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
    private final DinnerHouseholdInventoryMapper inventoryMapper;
    private final DinnerRecipeAuthorizer authorizer;
    private final DinnerRecipeCatalogAssembler catalogAssembler;
    private final RecipeMatchCalculator matchCalculator;

    @Autowired
    public DinnerRecipeService(
            DinnerRecipeMapper recipeMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipeCatalogAssembler catalogAssembler
    ) {
        this(recipeMapper, inventoryMapper, authorizer, catalogAssembler,
                new RecipeMatchCalculator());
    }

    DinnerRecipeService(
            DinnerRecipeMapper recipeMapper,
            DinnerHouseholdInventoryMapper inventoryMapper,
            DinnerRecipeAuthorizer authorizer,
            DinnerRecipeCatalogAssembler catalogAssembler,
            RecipeMatchCalculator matchCalculator
    ) {
        this.recipeMapper = recipeMapper;
        this.inventoryMapper = inventoryMapper;
        this.authorizer = authorizer;
        this.catalogAssembler = catalogAssembler;
        this.matchCalculator = matchCalculator;
    }

    public List<RecipeResponse> discover(
            Long userId,
            Set<Long> includeIngredientIds,
            Set<Long> excludeIngredientIds,
            boolean onlyCookable
    ) {
        RecipeAccess access = authorizer.requireMembership(userId);
        List<DinnerRecipeEntity> recipes = recipeMapper.selectList(
                Wrappers.<DinnerRecipeEntity>lambdaQuery()
                        .eq(DinnerRecipeEntity::getStatus, "PUBLISHED")
                        .and(visible -> visible
                                .eq(DinnerRecipeEntity::getScope, "SYSTEM")
                                .or(household -> household
                                        .eq(DinnerRecipeEntity::getScope, "HOUSEHOLD")
                                        .eq(DinnerRecipeEntity::getHouseholdId,
                                                access.householdId())))
                        .orderByAsc(DinnerRecipeEntity::getId));
        Map<Long, CatalogEntry> catalog = catalogAssembler.assemble(recipes);
        List<DinnerHouseholdInventoryEntity> inventory = inventoryMapper.selectList(
                Wrappers.<DinnerHouseholdInventoryEntity>lambdaQuery()
                        .eq(DinnerHouseholdInventoryEntity::getHouseholdId,
                                access.householdId()));

        Map<Long, Stock> householdStock = inventory.stream()
                .collect(Collectors.toMap(
                        DinnerHouseholdInventoryEntity::getIngredientId,
                        item -> new Stock(item.getQuantity(), item.getUnit())));

        return recipes.stream()
                .map(recipe -> catalog.get(recipe.getId()))
                .filter(java.util.Objects::nonNull)
                .map(entry -> response(
                        entry,
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
                        .eq(DinnerRecipeEntity::getStatus, "PUBLISHED")
                        .orderByAsc(DinnerRecipeEntity::getId))
                .stream()
                .map(RecipeResponse::from)
                .toList();
    }

    private RecipeResponse response(
            CatalogEntry entry,
            Map<Long, Stock> householdStock,
            Set<Long> includeIngredientIds,
            Set<Long> excludeIngredientIds
    ) {
        DinnerRecipeEntity recipe = entry.recipe();
        List<RecipeIngredientResponse> ingredients = entry.ingredients().stream()
                .sorted(Comparator.comparingInt(RecipeIngredientResponse::sortOrder))
                .toList();
        List<Requirement> requirements = ingredients.stream()
                .map(ingredient -> new Requirement(
                        ingredient.ingredientId(), ingredient.name(), ingredient.quantity(),
                        ingredient.unit(), ingredient.required(), ingredient.sortOrder()))
                .toList();
        Map<Long, Stock> effectiveStock = new HashMap<>(householdStock);
        for (Requirement requirement : requirements) {
            if (includeIngredientIds.contains(requirement.ingredientId())) {
                effectiveStock.putIfAbsent(
                        requirement.ingredientId(), new Stock(null, requirement.unit()));
            }
        }
        excludeIngredientIds.forEach(effectiveStock::remove);

        return new RecipeResponse(
                recipe.getId(), recipe.getName(), entry.imagePath(), recipe.getCategory(),
                recipe.getFlavor(), recipe.getEstimatedMinutes(), recipe.getScope(),
                "SYSTEM".equals(recipe.getScope()) ? 1L : recipe.getVersion(),
                entry.defaultMethod(), ingredients,
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

}
