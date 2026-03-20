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
