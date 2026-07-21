ALTER TABLE dinner_menu_selections
    ADD COLUMN recipe_version BIGINT NOT NULL DEFAULT 1 AFTER recipe_id,
    ADD COLUMN method_id BIGINT NULL AFTER recipe_version,
    ADD KEY idx_dinner_selections_method (method_id),
    ADD CONSTRAINT fk_dinner_selections_method
        FOREIGN KEY (method_id) REFERENCES dinner_recipe_methods (id);

ALTER TABLE dinner_record_dish_snapshots
    ADD COLUMN recipe_scope VARCHAR(16) NULL AFTER recipe_id,
    ADD COLUMN recipe_version BIGINT NULL AFTER recipe_scope,
    ADD COLUMN servings INT NULL AFTER estimated_minutes,
    ADD COLUMN method_id BIGINT NULL AFTER servings,
    ADD COLUMN method_name VARCHAR(64) NULL AFTER method_id,
    ADD COLUMN cooking_style VARCHAR(32) NULL AFTER method_name,
    ADD COLUMN method_steps JSON NULL AFTER cooking_style,
    ADD COLUMN ingredients JSON NULL AFTER method_steps;
