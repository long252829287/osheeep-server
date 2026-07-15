CREATE TABLE dinner_ingredients (
    id BIGINT NOT NULL AUTO_INCREMENT,
    scope VARCHAR(16) NOT NULL DEFAULT 'SYSTEM',
    household_id BIGINT NULL,
    owner_household_id BIGINT GENERATED ALWAYS AS (COALESCE(household_id, 0)) STORED,
    name VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    default_unit VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_ingredient_scope_name (scope, owner_household_id, name),
    KEY idx_dinner_ingredient_category_status (category, status),
    CONSTRAINT fk_dinner_ingredient_household
        FOREIGN KEY (household_id) REFERENCES dinner_households (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dinner_recipe_ingredients (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipe_id BIGINT NOT NULL,
    ingredient_id BIGINT NOT NULL,
    quantity DECIMAL(12,3) NULL,
    unit VARCHAR(16) NOT NULL,
    is_required TINYINT(1) NOT NULL DEFAULT 1,
    sort_order INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_recipe_ingredient (recipe_id, ingredient_id),
    KEY idx_dinner_recipe_ingredients_recipe (recipe_id, sort_order),
    CONSTRAINT fk_dinner_recipe_ingredient_recipe
        FOREIGN KEY (recipe_id) REFERENCES dinner_recipes (id),
    CONSTRAINT fk_dinner_recipe_ingredient_ingredient
        FOREIGN KEY (ingredient_id) REFERENCES dinner_ingredients (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dinner_household_inventory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    household_id BIGINT NOT NULL,
    ingredient_id BIGINT NOT NULL,
    quantity DECIMAL(12,3) NULL,
    unit VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_household_inventory (household_id, ingredient_id),
    KEY idx_dinner_inventory_household_updated (household_id, updated_at),
    CONSTRAINT fk_dinner_inventory_household
        FOREIGN KEY (household_id) REFERENCES dinner_households (id),
    CONSTRAINT fk_dinner_inventory_ingredient
        FOREIGN KEY (ingredient_id) REFERENCES dinner_ingredients (id),
    CONSTRAINT fk_dinner_inventory_updated_by
        FOREIGN KEY (updated_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO dinner_ingredients (scope, name, category, default_unit) VALUES
    ('SYSTEM', '番茄', '蔬菜', '个'),
    ('SYSTEM', '鸡蛋', '蛋奶', '枚'),
    ('SYSTEM', '牛肉', '肉类', '克'),
    ('SYSTEM', '油麦菜', '蔬菜', '克'),
    ('SYSTEM', '鸡肉', '肉类', '克'),
    ('SYSTEM', '大米', '主食', '克'),
    ('SYSTEM', '紫菜', '干货', '克'),
    ('SYSTEM', '鸡翅', '肉类', '只'),
    ('SYSTEM', '西兰花', '蔬菜', '克'),
    ('SYSTEM', '土豆', '蔬菜', '个'),
    ('SYSTEM', '青椒', '蔬菜', '个'),
    ('SYSTEM', '蒜', '调味料', '瓣'),
    ('SYSTEM', '葱', '调味料', '根'),
    ('SYSTEM', '姜', '调味料', '克'),
    ('SYSTEM', '食用油', '调味料', '毫升'),
    ('SYSTEM', '盐', '调味料', '克'),
    ('SYSTEM', '生抽', '调味料', '毫升'),
    ('SYSTEM', '可乐', '饮品', '毫升');

INSERT INTO dinner_recipe_ingredients
    (recipe_id, ingredient_id, quantity, unit, is_required, sort_order)
SELECT r.id, i.id, seed.quantity, seed.unit, seed.is_required, seed.sort_order
FROM (
    SELECT '番茄炒蛋' recipe_name, '番茄' ingredient_name, 2.000 quantity, '个' unit, 1 is_required, 1 sort_order
    UNION ALL SELECT '番茄炒蛋', '鸡蛋', 3.000, '枚', 1, 2
    UNION ALL SELECT '小炒黄牛肉', '牛肉', 300.000, '克', 1, 1
    UNION ALL SELECT '小炒黄牛肉', '青椒', 2.000, '个', 1, 2
    UNION ALL SELECT '清炒油麦菜', '油麦菜', 400.000, '克', 1, 1
    UNION ALL SELECT '清炒油麦菜', '蒜', 3.000, '瓣', 0, 2
    UNION ALL SELECT '黄焖鸡米饭', '鸡肉', 400.000, '克', 1, 1
    UNION ALL SELECT '黄焖鸡米饭', '大米', 200.000, '克', 1, 2
    UNION ALL SELECT '紫菜蛋花汤', '紫菜', 10.000, '克', 1, 1
    UNION ALL SELECT '紫菜蛋花汤', '鸡蛋', 2.000, '枚', 1, 2
    UNION ALL SELECT '可乐鸡翅', '鸡翅', 8.000, '只', 1, 1
    UNION ALL SELECT '可乐鸡翅', '可乐', 330.000, '毫升', 1, 2
    UNION ALL SELECT '蒜蓉西兰花', '西兰花', 400.000, '克', 1, 1
    UNION ALL SELECT '蒜蓉西兰花', '蒜', 4.000, '瓣', 1, 2
    UNION ALL SELECT '青椒土豆丝', '土豆', 2.000, '个', 1, 1
    UNION ALL SELECT '青椒土豆丝', '青椒', 1.000, '个', 1, 2
) seed
JOIN dinner_recipes r ON r.name = seed.recipe_name AND r.scope = 'SYSTEM'
JOIN dinner_ingredients i ON i.name = seed.ingredient_name AND i.scope = 'SYSTEM';
