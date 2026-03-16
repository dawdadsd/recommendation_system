CREATE TABLE user_behavior_aggregation (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    behavior_type VARCHAR(32)  NOT NULL,
    window_start  DATETIME(3)  NOT NULL,
    window_end    DATETIME(3)  NOT NULL,
    event_count   BIGINT       NOT NULL,
    avg_rating    DOUBLE,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    -- 幂等性：同一用户、同一行为、同一窗口只存一条
    UNIQUE KEY uk_user_behavior_window (user_id, behavior_type, window_start, window_end),
    -- 报表查询加速
    INDEX idx_user_window (user_id, window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
