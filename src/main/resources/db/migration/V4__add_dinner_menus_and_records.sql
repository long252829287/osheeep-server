CREATE TABLE dinner_recipes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    scope VARCHAR(16) NOT NULL,
    household_id BIGINT NULL,
    name VARCHAR(100) NOT NULL,
    image_path VARCHAR(255) NULL,
    category VARCHAR(32) NOT NULL,
    flavor VARCHAR(32) NOT NULL,
    estimated_minutes INT NOT NULL,
    creator_id BIGINT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_dinner_recipes_scope_status (scope, status),
    CONSTRAINT fk_dinner_recipes_household FOREIGN KEY (household_id) REFERENCES dinner_households (id),
    CONSTRAINT fk_dinner_recipes_creator FOREIGN KEY (creator_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dinner_menus (
    id BIGINT NOT NULL AUTO_INCREMENT,
    household_id BIGINT NOT NULL,
    menu_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    confirmed_by BIGINT NULL,
    confirmed_at DATETIME(3) NULL,
    completed_by BIGINT NULL,
    completed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_menu_business_date (household_id, menu_date),
    CONSTRAINT fk_dinner_menus_household FOREIGN KEY (household_id) REFERENCES dinner_households (id),
    CONSTRAINT fk_dinner_menus_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES users (id),
    CONSTRAINT fk_dinner_menus_completed_by FOREIGN KEY (completed_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dinner_menu_selections (
    id BIGINT NOT NULL AUTO_INCREMENT,
    menu_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    recipe_id BIGINT NOT NULL,
    selected_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_selection (menu_id, user_id, recipe_id),
    KEY idx_dinner_selections_menu (menu_id),
    CONSTRAINT fk_dinner_selections_menu FOREIGN KEY (menu_id) REFERENCES dinner_menus (id),
    CONSTRAINT fk_dinner_selections_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_dinner_selections_recipe FOREIGN KEY (recipe_id) REFERENCES dinner_recipes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dinner_menu_actions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    menu_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    action_type VARCHAR(16) NOT NULL,
    idempotency_key CHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_menu_action_key (idempotency_key),
    KEY idx_dinner_menu_actions_menu (menu_id),
    CONSTRAINT fk_dinner_actions_menu FOREIGN KEY (menu_id) REFERENCES dinner_menus (id),
    CONSTRAINT fk_dinner_actions_actor FOREIGN KEY (actor_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dinner_cooking_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    household_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    record_date DATE NOT NULL,
    completed_by BIGINT NOT NULL,
    completed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_record_menu (menu_id),
    KEY idx_dinner_records_household_completed (household_id, completed_at),
    CONSTRAINT fk_dinner_records_household FOREIGN KEY (household_id) REFERENCES dinner_households (id),
    CONSTRAINT fk_dinner_records_menu FOREIGN KEY (menu_id) REFERENCES dinner_menus (id),
    CONSTRAINT fk_dinner_records_completed_by FOREIGN KEY (completed_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dinner_record_dish_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    record_id BIGINT NOT NULL,
    recipe_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    image_path VARCHAR(255) NULL,
    category VARCHAR(32) NOT NULL,
    flavor VARCHAR(32) NOT NULL,
    estimated_minutes INT NOT NULL,
    selected_by_user_ids JSON NOT NULL,
    sort_order INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dinner_record_snapshot_order (record_id, sort_order),
    CONSTRAINT fk_dinner_snapshots_record FOREIGN KEY (record_id) REFERENCES dinner_cooking_records (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO dinner_recipes
    (scope, name, image_path, category, flavor, estimated_minutes)
VALUES
    ('SYSTEM', '番茄炒蛋', '/assets/recipes/tomato-eggs.jpg', '家常菜', '酸甜', 10),
    ('SYSTEM', '小炒黄牛肉', '/assets/recipes/stir-fried-beef.jpg', '下饭菜', '香辣', 15),
    ('SYSTEM', '清炒油麦菜', '/assets/recipes/sauteed-lettuce.jpg', '素菜', '清爽', 8),
    ('SYSTEM', '黄焖鸡米饭', '/assets/recipes/braised-chicken-rice.jpg', '下饭菜', '浓郁', 25),
    ('SYSTEM', '紫菜蛋花汤', '/assets/recipes/seaweed-egg-soup.jpg', '汤羹', '鲜香', 10),
    ('SYSTEM', '可乐鸡翅', '/assets/recipes/cola-chicken-wings.jpg', '家常菜', '咸甜', 30),
    ('SYSTEM', '蒜蓉西兰花', '/assets/recipes/garlic-broccoli.jpg', '素菜', '蒜香', 12),
    ('SYSTEM', '青椒土豆丝', '/assets/recipes/pepper-potato.jpg', '家常菜', '清爽', 12);
