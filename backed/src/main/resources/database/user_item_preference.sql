CREATE TABLE user_item_preference (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    item_id           BIGINT       NOT NULL,
    preference_score  DOUBLE       NOT NULL,
    interaction_count BIGINT       NOT NULL DEFAULT 0,
    last_window_start DATETIME(3)  NOT NULL,
    last_window_end   DATETIME(3)  NOT NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_user_item (user_id, item_id),
    INDEX idx_user_updated (user_id, updated_at),
    INDEX idx_item_updated (item_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ALS训练输入：用户商品偏好矩阵';
