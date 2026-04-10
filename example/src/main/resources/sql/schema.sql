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

-- 供应商连接调度表。
-- 这张表描述的是“平台如何与供应商执行同步调度”，不是供应商主数据。
CREATE TABLE supplier_connection (
    supplier_id            BIGINT        PRIMARY KEY,
    supplier_code          VARCHAR(64)   NOT NULL,
    status                 VARCHAR(32)   NOT NULL,
    pull_interval_seconds  INT           NOT NULL,
    next_pull_at           TIMESTAMP     NOT NULL,
    last_success_at        TIMESTAMP,
    last_error_at          TIMESTAMP,
    last_cursor            VARCHAR(256),
    retry_count            INT           NOT NULL,
    lease_until            TIMESTAMP,
    version                BIGINT        NOT NULL,
    created_at             TIMESTAMP     NOT NULL,
    updated_at             TIMESTAMP     NOT NULL
);

CREATE UNIQUE INDEX uk_supplier_connection_code
    ON supplier_connection (supplier_code);

CREATE INDEX idx_supplier_connection_schedule
    ON supplier_connection (status, next_pull_at);

CREATE INDEX idx_supplier_connection_lease
    ON supplier_connection (lease_until);

-- ─── 供应商拉取幂等表 ──────────────────────────────────────────────────────────
-- 防止 Kafka at-least-once 投递导致同一任务被重复执行。
-- 幂等键格式：{supplierId}_{yyyyMMddHHmm}，精度为分钟级（同一分钟内只执行一次）。
CREATE TABLE supplier_pull_idempotency (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    supplier_id      BIGINT       NOT NULL,
    idempotency_key  VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX uk_supplier_pull_idempotency_key
    ON supplier_pull_idempotency (idempotency_key);

CREATE INDEX idx_supplier_pull_idempotency_supplier
    ON supplier_pull_idempotency (supplier_id, created_at);

-- ─── 供应商拉取审计流水表 ──────────────────────────────────────────────────────
-- 每次拉取（成功、失败、跳过）均写一条审计记录，用于 SLA 统计和溯源。
-- outcome 枚举值：SUCCESS / FAILURE / SKIPPED_DUPLICATE / CIRCUIT_OPEN
CREATE TABLE supplier_pull_audit (
    id             BIGINT        AUTO_INCREMENT PRIMARY KEY,
    supplier_id    BIGINT        NOT NULL,
    supplier_code  VARCHAR(64)   NOT NULL,
    erp_type       VARCHAR(16)   NOT NULL,       -- KINGDEE / YONYOU / GENERIC
    outcome        VARCHAR(32)   NOT NULL,
    record_count   INT           NOT NULL DEFAULT 0,
    error_kind     VARCHAR(32),                  -- AUTH_FAILURE / RATE_LIMITED / ...
    error_message  VARCHAR(512),
    duration_ms    BIGINT        NOT NULL DEFAULT 0,
    executed_at    TIMESTAMP     NOT NULL
);

CREATE INDEX idx_supplier_pull_audit_supplier
    ON supplier_pull_audit (supplier_id, executed_at DESC);

CREATE INDEX idx_supplier_pull_audit_outcome
    ON supplier_pull_audit (outcome, executed_at DESC);

-- 标准化后的供应商结果表。
-- 一条 ERP 原始记录经过 parser 转换后，会以统一结构落到这里，
-- 供后续查询、分析或继续加工使用。
CREATE TABLE supplier_normalized_record (
    id                    BIGINT        AUTO_INCREMENT PRIMARY KEY,
    supplier_id           BIGINT        NOT NULL,
    supplier_code         VARCHAR(64)   NOT NULL,
    erp_type              VARCHAR(16)   NOT NULL,
    source_record_id      VARCHAR(128)  NOT NULL,
    source_business_code  VARCHAR(128)  NOT NULL,
    supplier_name         VARCHAR(255)  NOT NULL,
    source_supplier_status VARCHAR(64)  NOT NULL,
    supplier_status       VARCHAR(64)   NOT NULL,
    tax_no                VARCHAR(64)   NOT NULL,
    source_modified_at    VARCHAR(64)   NOT NULL,
    page_token            VARCHAR(256)  NOT NULL,
    next_page_token       VARCHAR(256)  NOT NULL,
    last_pulled_at        TIMESTAMP     NOT NULL,
    raw_item_json         CLOB          NOT NULL,
    created_at            TIMESTAMP     NOT NULL,
    updated_at            TIMESTAMP     NOT NULL
);

CREATE UNIQUE INDEX uk_supplier_normalized_record_source
    ON supplier_normalized_record (supplier_id, erp_type, source_record_id);

CREATE INDEX idx_supplier_normalized_record_supplier
    ON supplier_normalized_record (supplier_id, last_pulled_at DESC);

CREATE INDEX idx_supplier_normalized_record_business_code
    ON supplier_normalized_record (supplier_id, source_business_code);

CREATE TABLE etl_data_source_connection (
    id                 BIGINT        AUTO_INCREMENT PRIMARY KEY,
    connection_name    VARCHAR(64)   NOT NULL,
    data_source_type   VARCHAR(32)   NOT NULL,
    description        VARCHAR(255),
    config_json        CLOB          NOT NULL,
    created_at         TIMESTAMP     NOT NULL,
    updated_at         TIMESTAMP     NOT NULL
);

CREATE UNIQUE INDEX uk_etl_data_source_connection_name
    ON etl_data_source_connection (connection_name);

CREATE INDEX idx_etl_data_source_connection_type
    ON etl_data_source_connection (data_source_type);

CREATE TABLE etl_connection_test_log (
    id                 BIGINT        AUTO_INCREMENT PRIMARY KEY,
    connection_id      BIGINT        NOT NULL,
    data_source_type   VARCHAR(32)   NOT NULL,
    success            BOOLEAN       NOT NULL,
    message            VARCHAR(512)  NOT NULL,
    detail_json        CLOB,
    tested_at          TIMESTAMP     NOT NULL
);

CREATE INDEX idx_etl_connection_test_log_connection
    ON etl_connection_test_log (connection_id, tested_at DESC);

CREATE TABLE etl_ingestion_job (
    id                 BIGINT        AUTO_INCREMENT PRIMARY KEY,
    connection_id      BIGINT        NOT NULL,
    data_source_type   VARCHAR(32)   NOT NULL,
    status             VARCHAR(32)   NOT NULL,
    message            VARCHAR(512),
    request_json       CLOB,
    result_json        CLOB,
    started_at         TIMESTAMP     NOT NULL,
    finished_at        TIMESTAMP
);

CREATE INDEX idx_etl_ingestion_job_connection
    ON etl_ingestion_job (connection_id, started_at DESC);

CREATE TABLE etl_connection_security_audit (
    id                 BIGINT        AUTO_INCREMENT PRIMARY KEY,
    action             VARCHAR(32)   NOT NULL,
    connection_id      BIGINT,
    data_source_type   VARCHAR(32)   NOT NULL,
    actor_id           VARCHAR(128),
    client_ip          VARCHAR(64)   NOT NULL,
    target_summary     VARCHAR(512),
    resolved_addresses VARCHAR(512),
    success            BOOLEAN       NOT NULL,
    status             VARCHAR(32)   NOT NULL,
    message            VARCHAR(512)  NOT NULL,
    detail_json        CLOB,
    created_at         TIMESTAMP     NOT NULL
);

CREATE INDEX idx_etl_connection_security_audit_created
    ON etl_connection_security_audit (created_at DESC);
