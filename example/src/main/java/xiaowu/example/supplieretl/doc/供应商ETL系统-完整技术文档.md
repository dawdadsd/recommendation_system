# 供应商 ETL 系统 · 完整技术文档

> **版本**: v2.0 · **日期**: 2026-04-03  
> **模块**: `example` · **包路径**: `xiaowu.example.supplieretl`  
> **作者**: 本次 Copilot 会话完整实现记录

---

## 目录

1. [系统全景](#1-系统全景)
2. [六边形架构图](#2-六边形架构图)
3. [完整调度与执行时序图](#3-完整调度与执行时序图)
4. [ERP 双轨适配器架构](#4-erp-双轨适配器架构)
5. [熔断器三态状态机](#5-熔断器三态状态机)
6. [失败分类与处理流程](#6-失败分类与处理流程)
7. [Kafka 主题拓扑](#7-kafka-主题拓扑)
8. [数据库 ER 图](#8-数据库-er-图)
9. [万级供应商压测架构](#9-万级供应商压测架构)
10. [指数退避算法](#10-指数退避算法)
11. [配置速查表](#11-配置速查表)
12. [API 接口文档](#12-api-接口文档)

---

## 1. 系统全景

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                        供应商 ETL 系统 · 全景视图                                │
│                                                                                  │
│   ┌─────────┐   ┌─────────┐   REST API            ┌──────────────────────────┐  │
│   │ 金蝶云  │   │ 用友 BIP │  ───────────────────► │  Spring Boot 8924        │  │
│   │ 星空 ERP│   │ OpenAPI  │  Swagger UI           │  · @Scheduled 调度器     │  │
│   │ sandbox │   │ sandbox  │                       │  · Kafka Consumer         │  │
│   └────┬────┘   └────┬─────┘                       │  · HikariCP pool=30      │  │
│        │             │                             └───────────┬──────────────┘  │
│        │ HTTP/JSON   │ HTTP/JSON                               │                 │
│        ▼             ▼                                         │                 │
│   ┌─────────────────────────┐                     ┌───────────▼──────────────┐   │
│   │  RoutingSupplierPull    │   熔断 / 并发隔离    │  Apache Kafka            │   │
│   │  Client（路由 + CBR）   │                      │  · supplier.pull.request │   │
│   │  KD_* → KingdeeAdapter  │                      │    6 partitions          │   │
│   │  YY_* → YonyouAdapter   │                      │  · supplier.raw.data     │   │
│   │  其余 → MockClient      │                      │    12 partitions         │   │
│   └─────────────────────────┘                      │  · supplier.raw.dlq      │   │
│                                                    │    3 partitions          │   │
│   ┌─────────────────────────┐                     └───────────┬──────────────┘   │
│   │  H2 InMemory Database   │                                 │                  │
│   │  · supplier_connection  │   ◄──────────────────────────── │ Spark ETL        │
│   │  · supplier_pull_audit  │     乐观锁 / batchUpdate        │ (下游消费)       │
│   │  · supplier_pull_idempotency│                             └──────────────────┘  │
│   └─────────────────────────┘                                                   │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 六边形架构图

```mermaid
graph TB
    subgraph "🌐 Interfaces Layer"
        REST["🔌 REST Controllers<br/>SupplierSchedulingController<br/>SupplierLoadTestController"]
        SWAGGER["📖 Swagger UI<br/>/swagger-ui.html"]
    end

    subgraph "⚙️ Application Layer"
        SCHED_SVC["📋 SupplierPullScheduling<br/>ApplicationService<br/>扫描 → 抢租约 → 发布任务"]
        EXEC_SVC["🔄 SupplierPullExecution<br/>ApplicationService<br/>幂等 → 路由拉取 → 回写"]
    end

    subgraph "🏛️ Domain Layer"
        ENTITY["🏗️ SupplierConnection<br/>聚合根（状态机）<br/>ACTIVE / PAUSED / DISABLED"]
        REPO_IF["📂 SupplierConnectionRepository<br/>markPullSuccess / markPullFailed<br/>markSuspended / tryAcquireLease"]
    end

    subgraph "🔧 Infrastructure Layer"
        direction TB

        subgraph "📦 ERP Adapters"
            KD["🏭 KingdeeErpAdapter<br/>金蝶云星空<br/>Auth + ExecuteBillQuery<br/>cursor: date|startRow"]
            YY["🏢 YonyouErpAdapter<br/>用友 BIP<br/>OAuth2 Token + Page API<br/>cursor: date|pageIndex"]
            MOCK["🎭 MockSupplierPullClient<br/>fallback / 测试"]
        end

        subgraph "🛡️ Resilience"
            ROUTING["🔀 RoutingSupplierPullClient<br/>@Primary Bean<br/>前缀路由 + 孤岛保护"]
            CBR["⚡ SupplierCircuitBreaker<br/>CLOSED → OPEN → HALF_OPEN<br/>失败阈值=5 / 窗口=60s"]
        end

        subgraph "📨 Kafka"
            PUB_TASK["📤 KafkaPullTaskPublisher<br/>supplier.pull.request"]
            PUB_RAW["📤 KafkaRawDataPublisher<br/>supplier.raw.data<br/>partitionKey=supplierId"]
            CONSUMER["📥 SupplierPullTaskConsumer<br/>@KafkaListener"]
        end

        subgraph "🗄️ Persistence"
            JDBC_REPO["💾 JdbcSupplierConnectionRepository<br/>乐观锁 UPDATE WHERE version=?"]
            AUDIT_REPO["📝 JdbcSupplierPullAuditRepository<br/>幂等表 + 审计流水"]
        end

        subgraph "🧪 Load Test"
            NOOP["🚫 NoOpRawDataPublisher<br/>纯计数，无 Kafka 依赖<br/>mock-publisher=true 时启用"]
            LT_SVC["⚡ SupplierLoadTestService<br/>seed(batchUpdate) + run(虚拟线程)"]
        end
    end

    subgraph "🗄️ Data Stores"
        H2[("🗃️ H2 InMemory DB<br/>MySQL MODE")]
        KAFKA_BROKER[("📬 Apache Kafka<br/>localhost:9092")]
    end

    REST --> SCHED_SVC
    REST --> EXEC_SVC
    REST --> LT_SVC
    SWAGGER -.->|"文档" | REST
    SCHED_SVC --> REPO_IF
    SCHED_SVC --> PUB_TASK
    EXEC_SVC --> REPO_IF
    EXEC_SVC --> ROUTING
    EXEC_SVC --> PUB_RAW
    EXEC_SVC --> AUDIT_REPO
    REPO_IF --> JDBC_REPO
    ROUTING --> KD
    ROUTING --> YY
    ROUTING --> MOCK
    ROUTING --> CBR
    CONSUMER --> EXEC_SVC
    JDBC_REPO --> H2
    AUDIT_REPO --> H2
    PUB_TASK --> KAFKA_BROKER
    PUB_RAW --> KAFKA_BROKER
    CONSUMER --> KAFKA_BROKER
    LT_SVC --> JDBC_REPO
    LT_SVC --> EXEC_SVC

    style REST fill:#4A90D9,color:#fff
    style SCHED_SVC fill:#7B68EE,color:#fff
    style EXEC_SVC fill:#7B68EE,color:#fff
    style ENTITY fill:#2E8B57,color:#fff
    style KD fill:#FF6B35,color:#fff
    style YY fill:#FF6B35,color:#fff
    style CBR fill:#DC143C,color:#fff
    style ROUTING fill:#FF8C00,color:#fff
    style PUB_RAW fill:#20B2AA,color:#fff
    style CONSUMER fill:#20B2AA,color:#fff
    style H2 fill:#6495ED,color:#fff
    style KAFKA_BROKER fill:#232F3E,color:#fff
    style LT_SVC fill:#9370DB,color:#fff
```

---

## 3. 完整调度与执行时序图

```mermaid
sequenceDiagram
    autonumber

    participant CRON as ⏰ @Scheduled<br/>每10秒
    participant SCHED as 📋 Scheduling<br/>Service
    participant DB as 🗃️ H2 Database
    participant KAFKA as 📬 Kafka<br/>supplier.pull.request
    participant WORKER as 📥 KafkaConsumer<br/>Worker
    participant EXEC as 🔄 Execution<br/>Service
    participant ROUTING as 🔀 Routing<br/>Client
    participant ERP as 🏭 ERP Adapter<br/>(KD / YY / Mock)
    participant RAW_KAFKA as 📬 Kafka<br/>supplier.raw.data
    participant AUDIT as 📝 Audit<br/>Repository

    rect rgb(230, 245, 255)
        Note over CRON,KAFKA: 🕐 调度阶段：每 10 秒触发一次
        CRON->>SCHED: scanAndDispatch()
        SCHED->>DB: SELECT WHERE status='ACTIVE'<br/>AND next_pull_at <= now<br/>AND lease_until IS NULL<br/>LIMIT 50
        DB-->>SCHED: 候选供应商列表 [9001, 9002, ...]

        loop 每个候选供应商（串行抢锁）
            SCHED->>DB: UPDATE SET lease_until=now+30s, version=v+1<br/>WHERE supplier_id=? AND version=? AND lease_until IS NULL
            alt 抢锁成功（1行受影响）
                DB-->>SCHED: rowsAffected = 1
                SCHED->>KAFKA: send(PullRequestedEvent)<br/>key=supplierId (保证分区有序)
                KAFKA-->>SCHED: ack
            else 抢锁失败（被其他节点抢先）
                DB-->>SCHED: rowsAffected = 0
                Note over SCHED: 跳过，乐观锁 CAS 失败
            end
        end
    end

    rect rgb(245, 255, 230)
        Note over WORKER,AUDIT: 🔄 执行阶段：Kafka Consumer 异步消费
        KAFKA->>WORKER: onMessage(PullRequestedEvent)
        WORKER->>EXEC: execute(ExecuteCommand)

        Note over EXEC: 1️⃣ 幂等检查
        EXEC->>AUDIT: tryInsertIdempotency("supplierId_yyyyMMddHHmm")
        alt 已执行过（幂等命中）
            AUDIT-->>EXEC: false（DuplicateKeyException）
            EXEC-->>WORKER: ExecutionResult(success=true, skipped=true)
        else 首次执行
            AUDIT-->>EXEC: true

            Note over EXEC: 2️⃣ 加载最新状态
            EXEC->>DB: findBySupplierId(supplierId)
            DB-->>EXEC: SupplierConnection(version=N)

            Note over EXEC: 3️⃣ 路由拉取
            EXEC->>ROUTING: pull(PullCommand)
            ROUTING->>ROUTING: inFlight.putIfAbsent(pid) 并发槽检查
            ROUTING->>ROUTING: circuitBreaker.executeChecked()

            alt KD_前缀
                ROUTING->>ERP: KingdeeErpAdapter<br/>① POST /Auth → KDSVCSessionId<br/>② POST /ExecuteBillQuery<br/>cursor=date|startRow
            else YY_前缀
                ROUTING->>ERP: YonyouErpAdapter<br/>① POST /auth/token (OAuth2)<br/>② GET /supplier?page=N<br/>cursor=date|pageIndex
            else 其他前缀
                ROUTING->>ERP: MockSupplierPullClient<br/>返回合成数据
            end

            ERP-->>ROUTING: PullResult(records, nextCursor)
            ROUTING-->>EXEC: PullResult

            Note over EXEC: 4️⃣a 成功路径
            EXEC->>DB: markPullSuccess(cursor, version)<br/>SET retry_count=0, lease_until=NULL<br/>next_pull_at=now+interval
            EXEC->>RAW_KAFKA: publish(RawDataEvent)<br/>erpType=KINGDEE/YONYOU/GENERIC<br/>key=supplierId (Spark 无 shuffle)
            RAW_KAFKA-->>EXEC: ack

            EXEC->>AUDIT: insertAudit(outcome=SUCCESS, durationMs)
            EXEC-->>WORKER: ExecutionResult(success=true)
        end
    end

    rect rgb(255, 235, 235)
        Note over EXEC,AUDIT: ❌ 失败路径（失败分类处理）
        ERP-->>ROUTING: throw SupplierFetchException

        alt AUTH_FAILURE（401 / 凭证无效）
            ROUTING-->>EXEC: AuthException
            EXEC->>DB: markSuspended()<br/>SET status='SUSPENDED', lease_until=NULL
            Note over EXEC: 需人工修复凭证，停止自动重试
        else RATE_LIMITED（429 / 限流）
            ROUTING-->>EXEC: RateLimitedException(retryAfter)
            EXEC->>DB: markPullFailed(retryAt = max(retryAfter, backoff))
        else TIMEOUT / DATA_ERROR / UNAVAILABLE
            ROUTING-->>EXEC: TimeoutException / DataException / UnavailableException
            EXEC->>DB: markPullFailed(retryAt = exponentialBackoff)
            Note over EXEC: retryAt = min(base×2^retryCount, 3600s) + jitter
        end

        EXEC->>AUDIT: insertAudit(outcome=FAILURE, errorKind, durationMs)
        EXEC-->>WORKER: ExecutionResult(success=false)
    end
```

---

## 4. ERP 双轨适配器架构

```mermaid
graph LR
    subgraph "🔀 路由层"
        ROUTING["RoutingSupplierPullClient<br/>@Primary Bean"]
    end

    subgraph "🏭 金蝶云星空 KD_*"
        direction TB
        KD_AUTH["① POST /Auth<br/>acctId + username + md5(pwd)<br/>→ KDSVCSessionId"]
        KD_QUERY["② POST /ExecuteBillQuery<br/>FormId=BD_Supplier<br/>FilterString: FModifyDate > cursor"]
        KD_PAGE["游标格式<br/>date|startRow<br/>例: 2026-04-02 14:55:00|0"]
        KD_SAND["🏖️ sandbox=true<br/>生成 3 条合成记录<br/>无真实 HTTP 调用"]
        KD_AUTH --> KD_QUERY --> KD_PAGE
        KD_SAND -.->|"替代"| KD_QUERY
    end

    subgraph "🏢 用友 BIP YY_*"
        direction TB
        YY_TOKEN["① POST /api/v1/auth/token<br/>appId + appSecret (OAuth2)<br/>token 缓存 AtomicReference<br/>提前 60s 自动刷新"]
        YY_PAGE_API["② GET /api/v1/supplier<br/>?modifyAfter=date&page=N<br/>Bearer {token}"]
        YY_CURSOR["游标格式<br/>date|pageIndex<br/>例: 2026-04-02|1"]
        YY_RETRY["429 限流处理<br/>读 Retry-After Header<br/>→ RateLimitedException"]
        YY_TOKEN --> YY_PAGE_API --> YY_CURSOR
        YY_PAGE_API --> YY_RETRY
    end

    subgraph "🎭 通用 Fallback"
        MOCK["MockSupplierPullClient<br/>返回合成 JSON 数据<br/>GEN_FAIL_ONCE_* → 抛异常<br/>覆盖失败分支测试"]
    end

    subgraph "⚡ 熔断器（各 ERP 独立）"
        CBR_KD["SupplierCircuitBreaker<br/>kingdee-cb<br/>threshold=5 / window=60s"]
        CBR_YY["SupplierCircuitBreaker<br/>yonyou-cb<br/>threshold=5 / window=60s"]
        INFLIGHT["ConcurrentHashMap&lt;Long,Boolean&gt;<br/>inFlight<br/>per-supplier 并发槽<br/>同一供应商同时只允许 1 个"]
    end

    ROUTING --> |"KD_前缀"| CBR_KD --> KD_AUTH
    ROUTING --> |"YY_前缀"| CBR_YY --> YY_TOKEN
    ROUTING --> |"其他"| MOCK
    ROUTING --> INFLIGHT

    style KD_AUTH fill:#FF6B35,color:#fff
    style KD_QUERY fill:#FF6B35,color:#fff
    style KD_SAND fill:#FFA07A,color:#000
    style YY_TOKEN fill:#4169E1,color:#fff
    style YY_PAGE_API fill:#4169E1,color:#fff
    style YY_RETRY fill:#DC143C,color:#fff
    style CBR_KD fill:#DC143C,color:#fff
    style CBR_YY fill:#DC143C,color:#fff
    style INFLIGHT fill:#8B4513,color:#fff
    style MOCK fill:#696969,color:#fff
```

---

## 5. 熔断器三态状态机

```mermaid
stateDiagram-v2
    [*] --> CLOSED : 初始化

    state CLOSED {
        [*] --> 正常转发
        正常转发 --> 计数失败 : 调用抛异常
        计数失败 --> 正常转发 : 成功 → failureCount = 0
    }

    state OPEN {
        [*] --> 快速失败
        快速失败 --> 快速失败 : throw CircuitOpenException
    }

    state HALF_OPEN {
        [*] --> 探针检查
        探针检查 --> 探针通过 : probeInFlight = false\n且只允许 1 个请求
        探针检查 --> 仍然失败 : 探针调用抛异常
    }

    CLOSED --> OPEN : failureCount >= threshold(5)\n→ openSince = now
    OPEN --> HALF_OPEN : now - openSince >= openWindowMs(60s)
    HALF_OPEN --> CLOSED : 探针成功\n→ failureCount = 0
    HALF_OPEN --> OPEN : 探针失败\n→ 重新计时

    note right of CLOSED
        正常工作状态
        每次失败 failureCount++
        成功后 failureCount = 0
    end note

    note right of OPEN
        快速失败模式
        所有调用直接抛异常
        不经过实际 ERP HTTP
    end note

    note right of HALF_OPEN
        半开探针阶段
        只放行 1 个请求
        AtomicReference probeInFlight
        防止多线程竞争
    end note
```

**关键实现细节（`SupplierCircuitBreaker.java`）**：

| 属性 | 类型 | 作用 |
|------|------|------|
| `state` | `AtomicReference<State>` | 三态原子切换，无锁并发安全 |
| `failureCount` | `AtomicInteger` | 连续失败计数 |
| `openSince` | `AtomicLong` | OPEN 状态起始时间戳（ms） |
| `probeInFlight` | `AtomicReference<Boolean>` | HALF_OPEN 探针槽，CAS 保证唯一 |
| `failureThreshold` | `int` | 默认 5，超过则打开熔断 |
| `openWindowMs` | `long` | 默认 60000ms，OPEN 持续时长 |

---

## 6. 失败分类与处理流程

```mermaid
flowchart TD
    START(["🔄 execute(ExecuteCommand)"])

    subgraph "1️⃣ 幂等检查"
        IDEMP{"tryInsertIdempotency\nsupplierId_yyyyMMddHHmm"}
        SKIP["返回 SKIPPED\n(重复投递，安全忽略)"]
    end

    subgraph "2️⃣ 加载 & 路由"
        LOAD["findBySupplierId()\n加载 SupplierConnection"]
        ROUTE["RoutingSupplierPullClient.pull()\n熔断器 + 并发槽保护"]
    end

    subgraph "3️⃣ 结果处理"
        SUCCESS_BRANCH["✅ 成功路径"]
        FAIL_BRANCH{"❌ 失败分类\nSupplierFetchException"}
    end

    subgraph "✅ 成功操作"
        MARK_OK["markPullSuccess()\n· retry_count = 0\n· cursor 推进\n· next_pull_at = now + interval\n· lease_until = NULL"]
        PUB_RAW["publish(RawDataEvent)\n→ supplier.raw.data\nerpType标签让Spark选解析器"]
    end

    subgraph "❌ 失败分支"
        AUTH["🔐 AuthException\n→ markSuspended()\n  status = SUSPENDED\n  停止自动重试\n  需人工修凭证"]
        RATE["⏱️ RateLimitedException\n→ retryAt = max(\n    retryAfter Header,\n    exponentialBackoff\n  )"]
        TIMEOUT["⏰ TimeoutException\n→ exponentialBackoff\n  base=30s, max=3600s"]
        DATA["📛 DataException\n→ publishDlq(\n    supplier.raw.dlq\n  )\n→ exponentialBackoff"]
        UNAVAIL["💥 UnavailableException\n→ exponentialBackoff\n  (熔断器已计数)"]
    end

    subgraph "📝 审计落库（必执行）"
        AUDIT["insertAudit()\n· outcome: SUCCESS/FAILURE/SKIPPED\n· errorKind / errorMessage\n· durationMs\n· erpType"]
    end

    START --> IDEMP
    IDEMP -->|"已存在"| SKIP --> AUDIT
    IDEMP -->|"插入成功"| LOAD --> ROUTE

    ROUTE -->|"PullResult"| SUCCESS_BRANCH
    ROUTE -->|"抛出异常"| FAIL_BRANCH

    SUCCESS_BRANCH --> MARK_OK --> PUB_RAW --> AUDIT

    FAIL_BRANCH -->|"AuthException"| AUTH --> AUDIT
    FAIL_BRANCH -->|"RateLimitedException"| RATE --> AUDIT
    FAIL_BRANCH -->|"TimeoutException"| TIMEOUT --> AUDIT
    FAIL_BRANCH -->|"DataException"| DATA --> AUDIT
    FAIL_BRANCH -->|"UnavailableException"| UNAVAIL --> AUDIT

    style AUTH fill:#DC143C,color:#fff
    style RATE fill:#FF8C00,color:#fff
    style TIMEOUT fill:#DAA520,color:#000
    style DATA fill:#8B008B,color:#fff
    style UNAVAIL fill:#800000,color:#fff
    style MARK_OK fill:#228B22,color:#fff
    style PUB_RAW fill:#20B2AA,color:#fff
    style SKIP fill:#708090,color:#fff
    style AUDIT fill:#4682B4,color:#fff
```

**Sealed 异常层次结构**（`SupplierFetchException.java`）：

```java
sealed class SupplierFetchException extends RuntimeException
    permits AuthException,          // 401 凭证失效
            RateLimitedException,   // 429 携带 Instant retryAfter
            TimeoutException,       // 连接超时 / 读取超时
            DataException,          // 响应格式异常，携带 rawSnippet
            UnavailableException    // 熔断器快速失败
```

---

## 7. Kafka 主题拓扑

```mermaid
graph LR
    subgraph "🏭 生产者"
        SCHED_PUB["📤 KafkaPullTaskPublisher\nacks=all\nenable.idempotence=true\ncompression=lz4"]
        RAW_PUB["📤 KafkaRawDataPublisher\nkey=supplierId\n(保证同供应商同分区)"]
    end

    subgraph "📬 Apache Kafka Broker · localhost:9092"
        T1["📦 supplier.pull.request\n6 partitions | replicas=1\nretries=3 | acks=all"]
        T2["📦 supplier.raw.data\n12 partitions | replicas=1\n下游 Spark ETL 消费\npayload 含 erpType 标签"]
        T3["📦 supplier.raw.dlq\n3 partitions | replicas=1\n死信 / 人工排查\n数据格式异常落这里"]
    end

    subgraph "📥 消费者"
        CONSUMER["📥 SupplierPullTaskConsumer\ngroup-id: example-supplier-pull-worker\nauto-offset-reset: earliest"]
        SPARK["⚡ Spark Structured Streaming\n(下游，不在本模块)"]
    end

    SCHED_PUB -->|"PullRequestedEvent\nJSON{supplierId,cursor,...}"| T1
    RAW_PUB -->|"RawDataEvent\nJSON{records[], erpType}"| T2
    RAW_PUB -.->|"DlqEvent\n数据解析异常"| T3
    T1 --> CONSUMER
    T2 --> SPARK

    style T1 fill:#232F3E,color:#fff
    style T2 fill:#232F3E,color:#fff
    style T3 fill:#8B0000,color:#fff
    style SPARK fill:#E25A1C,color:#fff
```

**`erpType` 字段用途**：Kafka 消息体中携带 `erpType=KINGDEE|YONYOU|GENERIC`，  
Spark 消费端根据此字段选择对应的 Schema 解析器，**无需修改 Kafka 分区配置**即可扩展新 ERP 类型。

---

## 8. 数据库 ER 图

```mermaid
erDiagram
    supplier_connection {
        BIGINT supplier_id PK "业务主键"
        VARCHAR supplier_code "唯一业务编码 KD_/YY_/GEN_"
        VARCHAR status "ACTIVE/PAUSED/DISABLED/SUSPENDED"
        INT pull_interval_seconds "正常拉取间隔(秒)"
        TIMESTAMP next_pull_at "调度时钟★ 决定何时可调度"
        TIMESTAMP last_success_at "最后一次成功时间"
        TIMESTAMP last_error_at "最后一次失败时间"
        VARCHAR last_cursor "增量游标 date|offset"
        INT retry_count "连续失败次数 成功归0"
        TIMESTAMP lease_until "分布式租约锁到期时间"
        BIGINT version "乐观锁版本号"
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    supplier_pull_idempotency {
        BIGINT id PK
        BIGINT supplier_id FK
        VARCHAR idempotency_key UK "supplierId_yyyyMMddHHmm"
        TIMESTAMP created_at
    }

    supplier_pull_audit {
        BIGINT id PK
        BIGINT supplier_id FK
        VARCHAR supplier_code
        VARCHAR erp_type "KINGDEE/YONYOU/GENERIC"
        VARCHAR outcome "SUCCESS/FAILURE/SKIPPED_DUPLICATE"
        INT record_count "成功拉取记录数"
        VARCHAR error_kind "AUTH_FAILURE/RATE_LIMITED/..."
        VARCHAR error_message
        BIGINT duration_ms "执行耗时"
        TIMESTAMP executed_at
    }

    supplier_connection ||--o{ supplier_pull_idempotency : "每分钟一条幂等记录"
    supplier_connection ||--o{ supplier_pull_audit : "每次执行写一条审计"
```

**索引设计**：

```sql
-- 调度查询主索引（覆盖 WHERE status + next_pull_at 两个过滤条件）
CREATE INDEX idx_supplier_connection_schedule
    ON supplier_connection (status, next_pull_at);

-- 幂等唯一约束（DuplicateKeyException 触发乐观去重）
CREATE UNIQUE INDEX uk_supplier_pull_idempotency_key
    ON supplier_pull_idempotency (idempotency_key);

-- 租约过期扫描索引
CREATE INDEX idx_supplier_connection_lease
    ON supplier_connection (lease_until);
```

---

## 9. 万级供应商压测架构

```mermaid
graph TD
    subgraph "🎮 压测控制层"
        API["🔌 POST /api/examples/suppliers/load-test/seed\nPOST /api/examples/suppliers/load-test/run\nGET  /api/examples/suppliers/load-test/status\nDELETE /api/examples/suppliers/load-test"]
        CTRL["SupplierLoadTestController\n@RestController"]
    end

    subgraph "⚙️ 压测服务层（SupplierLoadTestService）"
        SEED["seed(SeedRequest)\n每批 500 行 batchUpdate\n总量上限 200,000\nID 从 1,000,000 起（不冲突）"]
        RUN["run(RunRequest)\n① 清理上轮幂等记录\n② 重置 next_pull_at = now-1s\n③ 加载全部 ACTIVE 压测 ID\n④ 并发执行"]
    end

    subgraph "🧵 并发模型"
        VIRTUAL["parallelism=0\nExecutors.newVirtualThread\nPerTaskExecutor()\n每供应商一个虚拟线程\n10w 线程仅占 ~100MB 堆\n阻塞时自动挂起，不消耗平台线程"]
        FIXED["parallelism=N\nExecutors.newFixedThreadPool(N)\n精确控制 DB 并发压力\n推荐 N=200（10万级）"]
    end

    subgraph "📊 指标收集"
        ADDER["LongAdder\nsuccessCount / failureCount / skippedCount\n高并发无竞争写入"]
        LATENCY["ConcurrentLinkedQueue&lt;Long&gt;\n收集每次延迟(ms)\n事后 sort → P99"]
        ATOMIC["AtomicLong maxLatency\n最大延迟原子更新"]
    end

    subgraph "🏷️ 供应商编码规则"
        KD_CODE["KD_LOAD_0000001 ~ N\n→ 金蝶沙盒\n必然成功"]
        YY_CODE["YY_LOAD_0000001 ~ N\n→ 用友沙盒\n必然成功"]
        FAIL_CODE["GEN_FAIL_ONCE_* \n→ MockClient\n首次抛异常\n覆盖失败分支"]
        GEN_CODE["GEN_LOAD_*\n→ MockClient\n正常返回"]
    end

    subgraph "📈 输出报告（LoadTestReport）"
        REPORT["totalActiveSuppliers: 执行总数\nexecutedCount: 完成数\nsuccessCount: 成功数\nfailureCount: 失败数\nskippedDuplicateCount: 幂等跳过\navgLatencyMs: 平均延迟\nmaxLatencyMs: 最大延迟\np99LatencyMs: P99 延迟\nthroughputPerSec: TPS\ntotalDurationMs: 总耗时\nstatus: COMPLETED/TIMEOUT"]
    end

    API --> CTRL --> SEED --> |"batchUpdate"| DB[("🗃️ H2 DB\nHikariCP pool=30")]
    CTRL --> RUN
    RUN --> VIRTUAL
    RUN --> FIXED
    VIRTUAL --> EXEC_SVC["🔄 SupplierPullExecution\nApplicationService\n完整真实链路"]
    FIXED --> EXEC_SVC
    EXEC_SVC --> ADDER
    EXEC_SVC --> LATENCY
    EXEC_SVC --> ATOMIC
    ADDER --> REPORT
    LATENCY --> REPORT
    ATOMIC --> REPORT

    KD_CODE -.->|"seed 时写入"| DB
    YY_CODE -.->|"seed 时写入"| DB
    FAIL_CODE -.->|"seed 时写入"| DB
    GEN_CODE -.->|"seed 时写入"| DB

    style VIRTUAL fill:#9370DB,color:#fff
    style FIXED fill:#4169E1,color:#fff
    style ADDER fill:#228B22,color:#fff
    style REPORT fill:#20B2AA,color:#fff
    style DB fill:#6495ED,color:#fff
```

**性能基准**（H2 内测数据）：

| 规模 | 并发模型 | HikariCP Pool | 预计耗时 | TPS 估算 |
|------|----------|---------------|----------|----------|
| 1 万 | 虚拟线程（parallelism=0） | 30 | 10~30s | 300~1000 |
| 1 万 | 固定线程（parallelism=100） | 30 | 15~40s | 250~700 |
| 10 万 | 固定线程（parallelism=200） | 50 | 60~120s | 800~1700 |

---

## 10. 指数退避算法

```mermaid
graph LR
    subgraph "📐 指数退避公式"
        FORMULA["delay = min(maxDelay, base × 2^retryCount) + random(0, jitter)"]
    end

    subgraph "📊 实际延迟对比"
        TABLE["retryCount │ 旧（固定45s） │ 新（base=30s, max=3600s）
        ─────────────────────────────────────────
             0       │     45s       │   30s + jitter(0~10s)
             1       │     45s       │   60s + jitter
             2       │     45s       │  120s + jitter
             3       │     45s       │  240s + jitter
             5       │     45s       │  960s ≈ 16 min
            10       │     45s       │ 3600s = 1 hour（封顶）"]
    end

    subgraph "🌊 1000 供应商同时失败场景"
        OLD["旧方案（固定45s）\nT+45s: 1000个同时到达\n→ 调度器瞬间峰值\n→ ERP 被洪水攻击"]
        NEW["新方案（退避+jitter）\nT+30~40s: 均匀散开\n失败2次: 60~70s后\n失败5次: 960s后\n→ 流量自然平滑"]
    end
```

**代码位置**（`SupplierConnection.calculateRetryAtWithBackoff()`）：

```java
long exponentialDelay = (long) baseDelaySeconds * (1L << Math.min(retryCount, 30));
long cappedDelay      = Math.min(exponentialDelay, maxDelaySeconds);
long jitterMs         = ThreadLocalRandom.current().nextLong(0, Math.max(1, maxJitterMs));

return baseTime.plusSeconds(cappedDelay).plusNanos(jitterMs * 1_000_000L);
```

**防溢出设计**：`Math.min(retryCount, 30)` 限制移位上限，`2^30 ≈ 10亿秒` 早已超过 `maxDelaySeconds=3600`，`Math.min` 会将其 cap 住，不会 overflow `long`。

---

## 11. 配置速查表

```yaml
supplier:
  # ─── Kafka 主题 ─────────────────────────────────────────────────────────────
  kafka:
    topic:
      pull-request: supplier.pull.request   # 6 partitions
      raw-data:     supplier.raw.data        # 12 partitions（Spark 消费）
      raw-dlq:      supplier.raw.dlq         # 3 partitions（死信）

  # ─── 调度器 ─────────────────────────────────────────────────────────────────
  scheduling:
    enabled: true
    fixed-delay-ms: 10000    # 调度频率：每 10 秒扫一次
    batch-size: 50           # 每轮最多调度 50 个
    lease-seconds: 30        # 租约时长：30 秒后自动超时重调度

  # ─── Worker 执行参数 ────────────────────────────────────────────────────────
  worker:
    enabled: true
    retry-delay-seconds: 45                  # 固定延迟（legacy，新代码用退避）
    retry-backoff-base-seconds: 30           # 退避基准值
    retry-backoff-max-seconds: 3600          # 退避上限：1 小时
    retry-backoff-max-jitter-ms: 10000       # 最大抖动：10 秒

  # ─── 压测开关 ────────────────────────────────────────────────────────────────
  load-test:
    mock-publisher: false   # false=真实Kafka | true=NoOp纯计数

  # ─── ERP 配置 ────────────────────────────────────────────────────────────────
  erp:
    kingdee:
      sandbox: true         # true=沙盒合成数据 | false=真实金蝶
      page-size: 100
      connect-timeout-ms: 5000
      read-timeout-ms: 15000
    yonyou:
      sandbox: true         # true=沙盒合成数据 | false=真实用友
      page-size: 100

# ─── HikariCP 连接池（压测关键参数）──────────────────────────────────────────
spring:
  datasource:
    hikari:
      maximum-pool-size: 30     # 10万级压测建议调至 50
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
```

---

## 12. API 接口文档

### 12.1 调度管理（`SupplierSchedulingController`）

| 方法 | 路径 | 功能 |
|------|------|------|
| `POST` | `/api/examples/suppliers/scheduling/trigger` | 手动触发一轮调度 |
| `GET`  | `/api/examples/suppliers/{supplierId}`        | 查询单个供应商状态 |

### 12.2 压测管理（`SupplierLoadTestController`）

Swagger UI：`http://localhost:8924/swagger-ui.html` → Tag: **Load Test**

| 方法 | 路径 | 功能 | 关键参数 |
|------|------|------|----------|
| `GET`    | `/api/examples/suppliers/load-test/status`      | 数据快照 | — |
| `POST`   | `/api/examples/suppliers/load-test/seed`         | 批量写入合成供应商 | `supplierCount`, `kdRatio`, `yyRatio`, `failRatio`, `reseed` |
| `POST`   | `/api/examples/suppliers/load-test/run`          | 并发执行压测 | `parallelism`, `timeoutSeconds`, `collectLatencies` |
| `POST`   | `/api/examples/suppliers/load-test/seed-and-run` | 一键写入+执行 | `seed{}`, `run{}` |
| `DELETE` | `/api/examples/suppliers/load-test`              | 清理压测数据 | — |

**标准请求示例（1万级压测）**：

```bash
# ① 写入 1 万供应商
curl -X POST http://localhost:8924/api/examples/suppliers/load-test/seed \
  -H "Content-Type: application/json" \
  -d '{
    "supplierCount": 10000,
    "kdRatio": 0.30,
    "yyRatio": 0.30,
    "failRatio": 0.05,
    "reseed": true
  }'
# 响应: {"totalSeeded":10000,"kingdeeCount":3000,"yonyouCount":3000,...}

# ② 虚拟线程并发执行，收集 P99
curl -X POST http://localhost:8924/api/examples/suppliers/load-test/run \
  -H "Content-Type: application/json" \
  -d '{
    "parallelism": 0,
    "timeoutSeconds": 120,
    "collectLatencies": true
  }'
# 响应: {"totalActiveSuppliers":10000,"executedCount":10000,
#         "successCount":9600,"failureCount":200,"skippedDuplicateCount":200,
#         "avgLatencyMs":8.3,"maxLatencyMs":342,"p99LatencyMs":67,
#         "throughputPerSec":1204.8,"totalDurationMs":8308,"status":"COMPLETED"}

# ③ 清理（演示数据 9001-9203 不受影响）
curl -X DELETE http://localhost:8924/api/examples/suppliers/load-test
```

---

## 附录：文件清单

### 本次新增文件

| 文件 | 归属层 | 职责 |
|------|--------|------|
| `application/port/SupplierFetchException.java` | Application/Port | Sealed 失败分类异常层次 |
| `application/port/RawDataPublisher.java` | Application/Port | 原始数据发布端口接口 |
| `infrastructure/adapter/kingdee/KingdeeErpAdapter.java` | Infrastructure | 金蝶云星空 HTTP 适配器 |
| `infrastructure/adapter/kingdee/KingdeeErpProperties.java` | Infrastructure | 金蝶配置 `@ConfigurationProperties` |
| `infrastructure/adapter/yonyou/YonyouErpAdapter.java` | Infrastructure | 用友 BIP OpenAPI 适配器 |
| `infrastructure/adapter/yonyou/YonyouErpProperties.java` | Infrastructure | 用友配置 `@ConfigurationProperties` |
| `infrastructure/adapter/RoutingSupplierPullClient.java` | Infrastructure | 前缀路由 + 熔断 + 并发隔离 `@Primary` |
| `infrastructure/resilience/SupplierCircuitBreaker.java` | Infrastructure | 手写三态熔断器（CLOSED/OPEN/HALF_OPEN）|
| `infrastructure/kafka/KafkaRawDataPublisher.java` | Infrastructure | `supplier.raw.data` + DLQ Kafka 发布者 |
| `infrastructure/audit/SupplierPullAuditRepository.java` | Infrastructure | 幂等 + 审计接口 |
| `infrastructure/audit/JdbcSupplierPullAuditRepository.java` | Infrastructure | JDBC 实现 |
| `infrastructure/loadtest/NoOpRawDataPublisher.java` | Infrastructure | 无 Kafka 依赖的纯计数发布者 |
| `infrastructure/loadtest/SupplierLoadTestService.java` | Infrastructure | 压测核心：seed + run + status + cleanup |
| `infrastructure/config/SupplierLoadTestConfiguration.java` | Infrastructure | 压测 Bean 配置（mock-publisher 开关）|
| `interfaces/rest/SupplierLoadTestController.java` | Interfaces | 压测 REST API（5 个接口）|

### 本次修改文件

| 文件 | 修改内容 |
|------|----------|
| `SupplierPullExecutionApplicationService.java` | **完整重写**：幂等→路由→失败分类→DLQ→审计 |
| `SupplierRuntimeConfiguration.java` | 注入全部新 Bean |
| `SupplierConnectionRepository.java` | 新增 `markSuspended()` |
| `JdbcSupplierConnectionRepository.java` | 实现 `markSuspended()` |
| `SupplierKafkaProperties.java` | Topic record 新增 `rawData` / `rawDlq` |
| `SupplierKafkaConfiguration.java` | 新增 rawData/DLQ topic Bean + `RawDataPublisher` Bean |
| `application.yml` | ERP 配置块 + HikariCP pool + load-test 开关 |
| `schema.sql` | 新增 `supplier_pull_idempotency` + `supplier_pull_audit` 表 |
| `data.sql` | 新增 KD_/YY_ 种子数据（9101~9203）|
| `example/pom.xml` | 覆盖 `java.version=21` 启用虚拟线程 API |

---

*文档由 GitHub Copilot 根据本次会话实现自动生成 · 2026-04-03*
