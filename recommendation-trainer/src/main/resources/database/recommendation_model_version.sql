CREATE TABLE recommendation_model_version (
    model_name       VARCHAR(64)  PRIMARY KEY,
    current_version  VARCHAR(64)  NOT NULL,
    previous_version VARCHAR(64),
    status           VARCHAR(32)  NOT NULL,
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                  ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐模型当前生效版本';
