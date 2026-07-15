package com.osheeep.server.dinner.recipe;

import com.osheeep.server.dinner.recipe.dto.RecipeMatchResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class RecipeMatchCalculator {

    public record Requirement(
            Long ingredientId,
            String name,
            BigDecimal quantity,
            String unit,
            boolean required,
            int sortOrder
    ) {
    }

    public record Stock(BigDecimal quantity, String unit) {
    }

    public RecipeMatchResponse calculate(
            List<Requirement> requirements, Map<Long, Stock> inventory) {
        List<String> missing = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        int totalRequired = 0;
        int matchedRequired = 0;

        for (Requirement requirement : requirements.stream()
                .sorted(Comparator.comparingInt(Requirement::sortOrder))
                .toList()) {
            if (!requirement.required()) {
                continue;
            }
            totalRequired++;
            Stock stock = inventory.get(requirement.ingredientId());
            if (stock == null || !stock.unit().equals(requirement.unit())) {
                missing.add(requirement.name());
            } else if (stock.quantity() == null || requirement.quantity() == null) {
                matchedRequired++;
                unknown.add(requirement.name());
            } else if (stock.quantity() != null && requirement.quantity() != null
                    && stock.quantity().compareTo(requirement.quantity()) < 0) {
                missing.add(requirement.name());
            } else {
                matchedRequired++;
            }
        }

        String status = !missing.isEmpty()
                ? "MISSING"
                : (!unknown.isEmpty() ? "UNKNOWN_QUANTITY" : "AVAILABLE");
        int matchPercent = totalRequired == 0
                ? 100
                : Math.round(matchedRequired * 100f / totalRequired);
        return new RecipeMatchResponse(
                status,
                matchedRequired,
                totalRequired,
                matchPercent,
                List.copyOf(missing),
                List.copyOf(unknown));
    }
}
