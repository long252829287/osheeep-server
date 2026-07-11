ALTER TABLE users
    MODIFY email VARCHAR(255) NULL,
    MODIFY password_hash VARCHAR(255) NULL;

CREATE TABLE wechat_user_identities (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    openid VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wechat_user_identities_user_id (user_id),
    UNIQUE KEY uk_wechat_user_identities_openid (openid),
    CONSTRAINT fk_wechat_user_identities_user_id FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
