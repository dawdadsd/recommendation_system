CREATE TABLE recommendation_job_checkpoint (
    job_name          VARCHAR(64)  PRIMARY KEY,
    last_processed_id BIGINT       NOT NULL DEFAULT 0,
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                   ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐任务处理进度';
