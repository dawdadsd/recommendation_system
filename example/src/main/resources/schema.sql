CREATE TABLE payment_order (
    order_no           VARCHAR(64)  PRIMARY KEY,
    idempotency_key    VARCHAR(64)  NOT NULL,
    user_id            BIGINT       NOT NULL,
    product_code       VARCHAR(64)  NOT NULL,
    amount_fen         BIGINT       NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    channel_trade_no   VARCHAR(64),
    paying_started_at  TIMESTAMP,
    paid_at            TIMESTAMP,
    closed_at          TIMESTAMP,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX uk_payment_order_idempotency
    ON payment_order (order_no, idempotency_key);

CREATE INDEX idx_payment_order_status_started_at
    ON payment_order (status, paying_started_at);

CREATE TABLE payment_demo_user (
    user_id      BIGINT       PRIMARY KEY,
    nickname     VARCHAR(64)  NOT NULL,
    account_tag  VARCHAR(64)  NOT NULL
);

CREATE TABLE payment_demo_product (
    product_code   VARCHAR(64)  PRIMARY KEY,
    product_name   VARCHAR(128) NOT NULL,
    amount_fen     BIGINT       NOT NULL,
    description    VARCHAR(255) NOT NULL
);

-- 秒杀库存真相表。
-- 这里把库存拆成 available / reserved / sold 三段，
-- 是为了把“抢到资格”和“真正卖出”明确区分开。
CREATE TABLE seckill_stock (
    sku_id             BIGINT       PRIMARY KEY,
    activity_id        BIGINT       NOT NULL,
    total_stock        INT          NOT NULL,
    available_stock    INT          NOT NULL,
    reserved_stock     INT          NOT NULL,
    sold_stock         INT          NOT NULL,
    version            BIGINT       NOT NULL,
    updated_at         TIMESTAMP    NOT NULL
);

CREATE INDEX idx_seckill_stock_activity
    ON seckill_stock (activity_id);

-- 秒杀资格表。
-- 这张表表达的是“用户拿到的限时占坑资格”，不是最终成交事实。
CREATE TABLE seckill_reservation (
    reservation_id      VARCHAR(64)  PRIMARY KEY,
    activity_id         BIGINT       NOT NULL,
    sku_id              BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    reservation_token   VARCHAR(64)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    payment_order_no    VARCHAR(64),
    expire_at           TIMESTAMP    NOT NULL,
    released_at         TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX uk_seckill_reservation_user
    ON seckill_reservation (activity_id, sku_id, user_id);

CREATE INDEX idx_seckill_reservation_status_expire
    ON seckill_reservation (status, expire_at);
