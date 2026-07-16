package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DinnerCustomRecipePersistenceContractTest {

    @Test
    void v6AddsVersionedRecipeAggregateMethodsStepsAndApprovedImages() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V6__add_household_custom_recipes.sql"));

        assertThat(sql).contains("CREATE TABLE dinner_image_assets");
        assertThat(sql).contains("ADD COLUMN version BIGINT NOT NULL DEFAULT 1");
        assertThat(sql).contains("CREATE TABLE dinner_recipe_methods");
        assertThat(sql).contains("CREATE TABLE dinner_recipe_method_steps");
        assertThat(sql).contains("UPDATE dinner_recipes");
        assertThat(sql).contains("status = 'PUBLISHED'");
        assertThat(sql).contains("0c9df553e9cc5ad1ae7e879dc753436ac60a89b8bb62eae70f2d02f18261e544");
    }

    @Test
    void recipeMapperExposesAggregateLock() throws Exception {
        assertThat(DinnerRecipeMapper.class.getMethod("selectByIdForUpdate", Long.class))
                .isNotNull();
    }

    @Test
    void discoveryQueriesMigratedPublishedSystemRecipes() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/osheeep/server/dinner/recipe/DinnerRecipeService.java"));
        String discoverMethod = source.substring(
                source.indexOf("public List<RecipeResponse> discover("),
                source.indexOf("public List<RecipeResponse> listSystemRecipes()"));

        assertThat(discoverMethod)
                .contains(".eq(DinnerRecipeEntity::getStatus, \"PUBLISHED\")")
                .doesNotContain(".eq(DinnerRecipeEntity::getStatus, \"ACTIVE\")");
    }
}
