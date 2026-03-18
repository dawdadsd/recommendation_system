CREATE TABLE user_item_preference_delta (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    item_id           BIGINT       NOT NULL,
    window_start      DATETIME(3)  NOT NULL,
    window_end        DATETIME(3)  NOT NULL,
    score_delta       DOUBLE       NOT NULL,
    interaction_delta BIGINT       NOT NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_user_item_window (user_id, item_id, window_start, window_end),
    INDEX idx_user_window (user_id, window_end),
    INDEX idx_item_window (item_id, window_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户商品偏好增量表';
