package com.osheeep.server.dinner.menu;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableField;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.record.entity.DinnerRecordDishSnapshotEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DinnerHouseholdRecipeMenuPersistenceContractTest {

    @Test
    void v7AddsSelectionIdentityAndImmutableSnapshotColumns() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V7__connect_household_recipes_to_menus.sql"));

        assertThat(sql).contains("ADD COLUMN recipe_version BIGINT NOT NULL DEFAULT 1");
        assertThat(sql).contains("ADD COLUMN method_id BIGINT NULL");
        assertThat(sql).contains("fk_dinner_selections_method");
        assertThat(sql).contains("ADD COLUMN recipe_scope VARCHAR(16) NULL");
        assertThat(sql).contains("ADD COLUMN method_steps JSON NULL");
        assertThat(sql).contains("ADD COLUMN ingredients JSON NULL");
        String snapshotAlter = sql.substring(
                sql.indexOf("ALTER TABLE dinner_record_dish_snapshots"));
        assertThat(snapshotAlter).doesNotContain("FOREIGN KEY");
    }

    @Test
    void entitiesExposeEveryV7Column() throws Exception {
        assertThat(DinnerMenuSelectionEntity.class.getMethod("getRecipeVersion")).isNotNull();
        assertThat(DinnerMenuSelectionEntity.class.getMethod("getMethodId")).isNotNull();
        assertThat(DinnerRecordDishSnapshotEntity.class.getMethod("getMethodStepsJson"))
                .isNotNull();
        assertThat(DinnerRecordDishSnapshotEntity.class.getMethod("getIngredientsJson"))
                .isNotNull();
        TableField ingredients = DinnerRecordDishSnapshotEntity.class
                .getDeclaredField("ingredientsJson")
                .getAnnotation(TableField.class);
        assertThat(ingredients).isNotNull();
        assertThat(ingredients.value()).isEqualTo("ingredients");
    }
}
