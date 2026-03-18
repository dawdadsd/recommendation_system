CREATE TABLE user_cf_recall (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_version VARCHAR(64)  NOT NULL,
    user_id       BIGINT       NOT NULL,
    item_id       BIGINT       NOT NULL,
    rank_position INT          NOT NULL,
    score         DOUBLE       NOT NULL,
    source        VARCHAR(32)  NOT NULL DEFAULT 'ALS',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    UNIQUE KEY uk_model_user_item (model_version, user_id, item_id),
    INDEX idx_user_model_rank (user_id, model_version, rank_position),
    INDEX idx_model_version (model_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ALS协同过滤召回结果';
