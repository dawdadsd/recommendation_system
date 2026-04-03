INSERT INTO payment_demo_user (user_id, nickname, account_tag) VALUES
    (1001, 'alice', 'new-user'),
    (1002, 'bob', 'active-user'),
    (1003, 'carol', 'vip-user');

INSERT INTO payment_demo_product (product_code, product_name, amount_fen, description) VALUES
    ('VIP_MONTH', '推荐系统会员月卡', 9900, '用于测试普通单次购买流程'),
    ('VIP_YEAR', '推荐系统会员年卡', 59900, '用于测试高金额支付单'),
    ('REPORT_PACK', '推荐效果分析报告包', 19900, '用于测试推荐系统增值服务购买');

INSERT INTO payment_order (
    order_no,
    idempotency_key,
    user_id,
    product_code,
    amount_fen,
    status,
    channel_trade_no,
    paying_started_at,
    paid_at,
    closed_at,
    created_at,
    updated_at
) VALUES
    (
        'PAY_DEMO_SUCCESS_001',
        'IDEMP_DEMO_SUCCESS_001',
        1001,
        'VIP_MONTH',
        9900,
        'SUCCESS',
        'WX202603200001',
        TIMESTAMP '2026-03-20 10:00:00',
        TIMESTAMP '2026-03-20 10:00:05',
        NULL,
        TIMESTAMP '2026-03-20 09:59:50',
        TIMESTAMP '2026-03-20 10:00:05'
    ),
    (
        'PAY_DEMO_PAYING_001',
        'IDEMP_DEMO_PAYING_001',
        1002,
        'REPORT_PACK',
        19900,
        'PAYING',
        NULL,
        TIMESTAMP '2026-03-20 11:00:00',
        NULL,
        NULL,
        TIMESTAMP '2026-03-20 10:59:50',
        TIMESTAMP '2026-03-20 11:00:00'
    ),
    (
        'PAY_DEMO_CLOSED_001',
        'IDEMP_DEMO_CLOSED_001',
        1003,
        'VIP_YEAR',
        59900,
        'CLOSED',
        NULL,
        NULL,
        NULL,
        TIMESTAMP '2026-03-20 12:10:00',
        TIMESTAMP '2026-03-20 12:00:00',
        TIMESTAMP '2026-03-20 12:10:00'
    );

INSERT INTO seckill_stock (
    sku_id,
    activity_id,
    total_stock,
    available_stock,
    reserved_stock,
    sold_stock,
    version,
    updated_at
) VALUES
    (
        20001,
        30001,
        100,
        97,
        2,
        1,
        3,
        TIMESTAMP '2026-03-23 10:00:00'
    ),
    (
        20002,
        30001,
        20,
        20,
        0,
        0,
        0,
        TIMESTAMP '2026-03-23 10:00:00'
    );

INSERT INTO seckill_reservation (
    reservation_id,
    activity_id,
    sku_id,
    user_id,
    reservation_token,
    status,
    payment_order_no,
    expire_at,
    released_at,
    created_at,
    updated_at
) VALUES
    (
        'RSV_DEMO_ORDER_CREATED_001',
        30001,
        20001,
        1001,
        'TOKEN_DEMO_ORDER_CREATED_001',
        'ORDER_CREATED',
        'PAY_DEMO_PAYING_001',
        TIMESTAMP '2026-03-23 10:05:00',
        NULL,
        TIMESTAMP '2026-03-23 10:00:00',
        TIMESTAMP '2026-03-23 10:00:05'
    ),
    (
        'RSV_DEMO_PAID_001',
        30001,
        20001,
        1002,
        'TOKEN_DEMO_PAID_001',
        'PAID',
        'PAY_DEMO_SUCCESS_001',
        TIMESTAMP '2026-03-23 10:05:00',
        NULL,
        TIMESTAMP '2026-03-23 09:59:50',
        TIMESTAMP '2026-03-23 10:00:05'
    ),
    (
        'RSV_DEMO_RELEASED_001',
        30001,
        20001,
        1003,
        'TOKEN_DEMO_RELEASED_001',
        'RELEASED',
        NULL,
        TIMESTAMP '2026-03-23 09:58:00',
        TIMESTAMP '2026-03-23 10:01:00',
        TIMESTAMP '2026-03-23 09:55:00',
        TIMESTAMP '2026-03-23 10:01:00'
    );

INSERT INTO supplier_connection (
    supplier_id,
    supplier_code,
    status,
    pull_interval_seconds,
    next_pull_at,
    last_success_at,
    last_error_at,
    last_cursor,
    retry_count,
    lease_until,
    version,
    created_at,
    updated_at
) VALUES
    (
        9001,
        'SUPPLIER_ALPHA',
        'ACTIVE',
        60,
        TIMESTAMP '2026-04-02 15:00:00',
        TIMESTAMP '2026-04-02 14:59:00',
        NULL,
        'cursor-9001-v1',
        0,
        NULL,
        0,
        TIMESTAMP '2026-04-02 14:50:00',
        TIMESTAMP '2026-04-02 14:59:00'
    ),
    (
        9002,
        'SUPPLIER_FAIL_ONCE',
        'ACTIVE',
        120,
        TIMESTAMP '2026-04-02 15:00:00',
        NULL,
        NULL,
        NULL,
        0,
        NULL,
        0,
        TIMESTAMP '2026-04-02 14:50:00',
        TIMESTAMP '2026-04-02 14:50:00'
    ),
    (
        9003,
        'SUPPLIER_PAUSED',
        'PAUSED',
        300,
        TIMESTAMP '2026-04-02 15:00:00',
        NULL,
        NULL,
        NULL,
        0,
        NULL,
        0,
        TIMESTAMP '2026-04-02 14:50:00',
        TIMESTAMP '2026-04-02 14:50:00'
    );

-- ─── 金蝶云星空（KD_ 前缀）供应商连接种子数据 ───────────────────────────────────
-- sandbox=true 时适配器返回内置 mock 数据，无需真实金蝶环境；
-- 将 application.yml 中 supplier.erp.kingdee.sandbox 改为 false 并填写真实凭证即可接入真实 ERP。
INSERT INTO supplier_connection (
    supplier_id, supplier_code, status, pull_interval_seconds,
    next_pull_at, last_success_at, last_error_at, last_cursor,
    retry_count, lease_until, version, created_at, updated_at
) VALUES
    (
        -- 金蝶供应商-1：正常运行，已完成过一轮拉取
        9101,
        'KD_SUPPLIER_HANGZHOU',
        'ACTIVE',
        300,
        TIMESTAMP '2026-04-02 15:00:00',
        TIMESTAMP '2026-04-02 14:55:00',
        NULL,
        '2026-04-02 14:55:00|0',
        0,
        NULL,
        1,
        TIMESTAMP '2026-04-02 14:00:00',
        TIMESTAMP '2026-04-02 14:55:00'
    ),
    (
        -- 金蝶供应商-2：待首次拉取，用于演示分页游标从零开始
        9102,
        'KD_SUPPLIER_SHENZHEN',
        'ACTIVE',
        600,
        TIMESTAMP '2026-04-02 15:00:00',
        NULL,
        NULL,
        NULL,
        0,
        NULL,
        0,
        TIMESTAMP '2026-04-02 14:00:00',
        TIMESTAMP '2026-04-02 14:00:00'
    ),
    (
        -- 金蝶供应商-3：模拟鉴权失败被挂起（AUTH_FAILURE → SUSPENDED）
        9103,
        'KD_SUPPLIER_SUSPENDED',
        'SUSPENDED',
        300,
        TIMESTAMP '2026-04-02 15:00:00',
        NULL,
        TIMESTAMP '2026-04-02 14:30:00',
        NULL,
        3,
        NULL,
        5,
        TIMESTAMP '2026-04-02 14:00:00',
        TIMESTAMP '2026-04-02 14:30:00'
    );

-- ─── 用友 BIP（YY_ 前缀）供应商连接种子数据 ──────────────────────────────────────
-- sandbox=true 时适配器返回内置 mock 数据，无需真实用友 BIP 环境。
INSERT INTO supplier_connection (
    supplier_id, supplier_code, status, pull_interval_seconds,
    next_pull_at, last_success_at, last_error_at, last_cursor,
    retry_count, lease_until, version, created_at, updated_at
) VALUES
    (
        -- 用友供应商-1：正常运行
        9201,
        'YY_SUPPLIER_BEIJING',
        'ACTIVE',
        300,
        TIMESTAMP '2026-04-02 15:00:00',
        TIMESTAMP '2026-04-02 14:56:00',
        NULL,
        '2026-04-02|1',
        0,
        NULL,
        1,
        TIMESTAMP '2026-04-02 14:00:00',
        TIMESTAMP '2026-04-02 14:56:00'
    ),
    (
        -- 用友供应商-2：曾被限流，等待重试（RATE_LIMITED 场景）
        9202,
        'YY_SUPPLIER_SHANGHAI',
        'ACTIVE',
        600,
        TIMESTAMP '2026-04-02 16:00:00',
        TIMESTAMP '2026-04-02 13:00:00',
        TIMESTAMP '2026-04-02 14:00:00',
        '2026-04-02|1',
        2,
        NULL,
        3,
        TIMESTAMP '2026-04-02 12:00:00',
        TIMESTAMP '2026-04-02 14:00:00'
    );
