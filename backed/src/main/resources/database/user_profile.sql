-- 用户基础画像（静态属性）
-- 冷启动基础：新用户无行为数据时，AI 依赖此表进行个性化对话
CREATE TABLE user_profile (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL UNIQUE,
    nickname        VARCHAR(64),
    gender          VARCHAR(8)   NOT NULL DEFAULT 'UNKNOWN' COMMENT 'MALE / FEMALE / UNKNOWN',
    age_range       VARCHAR(16)  COMMENT '18-24 / 25-34 / 35-44 / 45+',
    region          VARCHAR(64),
    preference_tags VARCHAR(256) COMMENT '逗号分隔的偏好标签, 如: 3C数码,运动户外,美妆',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    INDEX idx_gender_age (gender, age_range)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户基础画像';
