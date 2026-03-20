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
