CREATE TABLE dinner_image_assets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider VARCHAR(32) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    search_keywords VARCHAR(255) NOT NULL,
    source_page_url VARCHAR(512) NOT NULL,
    original_file_url VARCHAR(512) NOT NULL,
    author VARCHAR(120) NOT NULL,
    license_name VARCHAR(120) NOT NULL,
    license_url VARCHAR(512) NOT NULL,
    acquired_on DATE NOT NULL,
    sha256 CHAR(64) NOT NULL,
    original_width INT NOT NULL,
    original_height INT NOT NULL,
    original_object_key VARCHAR(255) NOT NULL,
    list_object_key VARCHAR(255) NOT NULL,
    detail_object_key VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    reviewed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_image_assets_sha256 (sha256),
    KEY idx_dinner_image_assets_status_id (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE dinner_recipes
    MODIFY name VARCHAR(100) NULL,
    MODIFY category VARCHAR(32) NULL,
    MODIFY flavor VARCHAR(32) NULL,
    MODIFY estimated_minutes INT NULL,
    MODIFY status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN servings INT NULL AFTER flavor,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1 AFTER status,
    ADD COLUMN image_asset_id BIGINT NULL AFTER image_path,
    ADD COLUMN last_modified_by BIGINT NULL AFTER creator_id,
    ADD COLUMN source_recipe_id BIGINT NULL AFTER last_modified_by,
    ADD COLUMN revision_of_recipe_id BIGINT NULL AFTER source_recipe_id,
    ADD COLUMN base_published_version BIGINT NULL AFTER revision_of_recipe_id,
    ADD COLUMN published_at DATETIME(3) NULL AFTER base_published_version,
    ADD COLUMN archived_at DATETIME(3) NULL AFTER published_at,
    ADD KEY idx_dinner_recipes_household_status (household_id, status, id),
    ADD KEY idx_dinner_recipes_creator_status (creator_id, status, id),
    ADD CONSTRAINT fk_dinner_recipes_image_asset FOREIGN KEY (image_asset_id) REFERENCES dinner_image_assets (id),
    ADD CONSTRAINT fk_dinner_recipes_last_modified_by FOREIGN KEY (last_modified_by) REFERENCES users (id),
    ADD CONSTRAINT fk_dinner_recipes_source FOREIGN KEY (source_recipe_id) REFERENCES dinner_recipes (id),
    ADD CONSTRAINT fk_dinner_recipes_revision FOREIGN KEY (revision_of_recipe_id) REFERENCES dinner_recipes (id);

CREATE TABLE dinner_recipe_methods (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipe_id BIGINT NOT NULL,
    name VARCHAR(64) NULL,
    cooking_style VARCHAR(32) NULL,
    estimated_minutes INT NULL,
    is_default TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    sort_order INT NOT NULL DEFAULT 0,
    default_recipe_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN is_default = 1 AND status = 'ACTIVE' THEN recipe_id ELSE NULL END
    ) STORED,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_recipe_default_method (default_recipe_id),
    KEY idx_dinner_recipe_methods_recipe (recipe_id, status, sort_order),
    CONSTRAINT fk_dinner_recipe_methods_recipe FOREIGN KEY (recipe_id) REFERENCES dinner_recipes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dinner_recipe_method_steps (
    id BIGINT NOT NULL AUTO_INCREMENT,
    method_id BIGINT NOT NULL,
    instruction VARCHAR(160) NOT NULL,
    sort_order INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_recipe_method_step_order (method_id, sort_order),
    CONSTRAINT fk_dinner_recipe_method_steps_method FOREIGN KEY (method_id) REFERENCES dinner_recipe_methods (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

UPDATE dinner_recipes
SET status = 'PUBLISHED',
    version = 1,
    published_at = created_at
WHERE scope = 'SYSTEM' AND status = 'ACTIVE';

INSERT INTO dinner_image_assets (
    provider, display_name, search_keywords,
    source_page_url, original_file_url, author, license_name, license_url,
    acquired_on, sha256, original_width, original_height, original_object_key,
    list_object_key, detail_object_key, status, reviewed_at
) VALUES (
    'WIKIMEDIA_COMMONS', '番茄炒鸡蛋', '番茄 西红柿 鸡蛋 家常菜',
    'https://commons.wikimedia.org/wiki/File:Tomato_with_egg.jpg',
    'https://upload.wikimedia.org/wikipedia/commons/5/56/Tomato_with_egg.jpg',
    'Kaap bij Sneeuw', 'CC0 1.0',
    'https://creativecommons.org/publicdomain/zero/1.0/',
    '2026-07-16',
    '0c9df553e9cc5ad1ae7e879dc753436ac60a89b8bb62eae70f2d02f18261e544',
    1198, 1091,
    'internal/recipes/tomato-with-egg/original.jpg',
    'media/recipes/tomato-with-egg-list.webp',
    'media/recipes/tomato-with-egg-detail.webp',
    'APPROVED', CURRENT_TIMESTAMP(3)
);
