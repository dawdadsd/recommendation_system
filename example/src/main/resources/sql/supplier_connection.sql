CREATE TABLE supplier_connection (
    supplier_id            BIGINT        PRIMARY KEY COMMENT '平台内部供应商连接 ID，主键',
    supplier_code          VARCHAR(64)   NOT NULL UNIQUE COMMENT '供应商编码，便于排障、检索和外部对接',
    status                 VARCHAR(32)   NOT NULL COMMENT '调度状态：ACTIVE / PAUSED / DISABLED',
    pull_interval_seconds  INT           NOT NULL COMMENT '正常拉取间隔，单位秒',
    next_pull_at           TIMESTAMP     NOT NULL COMMENT '下一次允许被调度的时间点',
    last_success_at        TIMESTAMP     NULL COMMENT '最近一次拉取成功时间',
    last_error_at          TIMESTAMP     NULL COMMENT '最近一次拉取失败时间',
    last_cursor            VARCHAR(256)  NULL COMMENT '增量同步游标，可存时间戳、页号、token、外部主键等',
    retry_count            INT           NOT NULL COMMENT '连续失败后的重试次数',
    lease_until            TIMESTAMP     NULL COMMENT '当前租约到期时间；未过期表示该连接已被某个调度实例领走',
    version                BIGINT        NOT NULL COMMENT '乐观锁版本号，防止多个调度器并发覆盖',
    created_at             TIMESTAMP     NOT NULL COMMENT '创建时间',
    updated_at             TIMESTAMP     NOT NULL COMMENT '最后更新时间'
) COMMENT='供应商连接调度表：描述平台如何与供应商执行同步调度，而不是供应商主数据';
