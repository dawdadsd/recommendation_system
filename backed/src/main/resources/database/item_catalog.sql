-- 商品信息表
-- AI 对话时需要知道商品详情，才能生成有意义的推荐理由
CREATE TABLE item_catalog (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id     BIGINT         NOT NULL UNIQUE,
    name        VARCHAR(128)   NOT NULL,
    category    VARCHAR(64)    NOT NULL COMMENT '商品品类: 手机, 耳机, 运动鞋',
    price       DECIMAL(10, 2),
    tags        VARCHAR(256)   COMMENT '商品标签, 逗号分隔: 折叠屏,旗舰,5G',
    description TEXT,
    created_at  DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    INDEX idx_category (category),
    INDEX idx_price (price)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品信息';
