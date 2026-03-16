# Real-time Recommendation System

基于 **Spring Boot 3.5 + Kafka + Spark 4.0 Structured Streaming** 的实时推荐系统。

系统从 Kafka 消费用户行为事件，通过 Spark 双流水线并行处理：一条聚合用户行为写入 MySQL 用于报表分析，另一条计算物品偏好评分生成 Top-N 推荐结果写回 Kafka。

---

## 系统架构

```
                         ┌──────────────────────────────┐
                         │      StreamController         │
                         │  POST /start /stop /event     │
                         └──────────┬───────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼                               ▼
          StreamSimulatorService            手动发送单条事件
          (模拟器：100用户×500商品)
                    │
                    ▼
          BehaviorEventProducer
          (JSON 序列化 → Kafka)
                    │
                    ▼
        ┌───────────────────────┐
        │   Kafka: user-events  │
        └───────────┬───────────┘
                    │
                    ▼
        KafkaEventDeserializer
        (binary → 结构化 DataFrame)
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
    流水线 1: 聚合         流水线 2: 推荐
    30s窗口聚合            偏好评分计算
    userId×behaviorType    userId×itemId
          │                   │
          ▼                   ▼
    MySQL (upsert)      Kafka: recommendations
    行为报表              Top-N 推荐结果
```

---

## 已实现的功能

### 核心流处理

- **双流水线架构**：两条 Spark Streaming 查询共享同一个 Kafka 数据源，Spark 内部优化为单次读取
- **行为聚合流水线**：30 秒滚动窗口，按 `userId × behaviorType` 聚合 `eventCount` 和 `avgRating`，通过 JDBC upsert 幂等写入 MySQL
- **推荐评分流水线**：30 秒滚动窗口，按 `userId × itemId` 计算偏好总分，取每用户 Top-N 推荐结果写入 Kafka

### 偏好评分模型

| 行为类型     | 分值      | 含义                     |
| :----------- | :-------- | :----------------------- |
| VIEW / CLICK | 1.0       | 弱兴趣信号               |
| ADD_TO_CART  | 3.0       | 中等购买意图             |
| PURCHASE     | 5.0       | 强转化信号               |
| RATE         | 0.0 - 4.0 | 用户主动评分，归一化处理 |

### DDD 领域模型

- **Entity**：`BehaviorEvent`（Builder 模式构建 + 参数校验）
- **Value Object**：`BehaviorType`（枚举，含权重定义）、`Rating`（值对象，范围 1.0-5.0）
- **Aggregate**：`UserBehaviorAggregate`（用户行为聚合根）
- **Domain Service**：`BehaviorAnalysisService`（异常行为检测：1 小时 >1000 事件判定异常）

### Kafka 生产者

- 幂等生产者（`enable.idempotence=true`）
- `acks=all` 确保所有 ISR 副本确认
- LZ4 压缩，批量发送优化吞吐
- userId 作为 partition key，保证同一用户的事件有序

### 数据库持久化

- `user_behavior_aggregation` 表存储窗口聚合结果
- 唯一约束 `(user_id, behavior_type, window_start, window_end)` 保证幂等
- `ON DUPLICATE KEY UPDATE` 处理 Spark checkpoint 重放场景

### 生命周期管理

- `SmartLifecycle` 集成，精确控制 Spark 启停顺序（先于 Kafka/DB 关闭）
- CAS 状态机（STOPPED → STARTING → RUNNING → STOPPING）防止并发启停
- Daemon 线程兜底，防止 JVM 僵死

### REST API

| 方法 | 路径                                    | 说明                         |
| :--- | :-------------------------------------- | :--------------------------- |
| POST | `/api/stream/start?eventsPerSecond=<n>` | 启动模拟器 + Spark Streaming |
| POST | `/api/stream/stop`                      | 停止所有处理                 |
| GET  | `/api/stream/status`                    | 查询运行状态                 |
| POST | `/api/stream/event`                     | 手动发送单条事件（调试用）   |

### 压力测试

- `KafkaConcurrentLoadTest`：200 线程并发发送 10 万条事件，验证生产者吞吐量和可靠性

---

## 项目结构

```
backed/src/main/java/xiaowu/backed/
├── Application.java                              # Spring Boot 入口
├── interfaces/rest/
│   └── StreamController.java                     # REST API 控制器
├── application/
│   ├── dto/
│   │   ├── BehaviorEventDTO.java                 # 行为事件 DTO
│   │   ├── RecommendedItemDTO.java               # 推荐项 DTO (rank, itemId, score)
│   │   └── UserRecommendationDTO.java            # 用户推荐结果 DTO
│   └── service/
│       └── StreamSimulatorService.java           # 事件模拟器
├── domain/
│   ├── UserBehavior.java
│   └── eventburial/
│       ├── entity/BehaviorEvent.java             # 事件实体 (Builder 模式)
│       ├── valueobject/
│       │   ├── BehaviorType.java                 # 行为枚举 (含权重)
│       │   └── Rating.java                       # 评分值对象
│       ├── aggregate/UserBehaviorAggregate.java  # 聚合根
│       └── service/BehaviorAnalysisService.java  # 异常检测服务
└── infrastructure/
    ├── kafka/
    │   ├── BehaviorEventProducer.java            # Kafka 生产者
    │   ├── KafkaProducerConfig.java              # 生产者配置
    │   └── KafkaTopicConfig.java                 # Topic 定义
    └── spark/
        ├── BehaviorStreamProcessor.java          # Spark 双流水线处理器
        └── KafkaEventDeserializer.java           # Kafka 消息反序列化
```

---

## 技术栈

| 组件         | 版本  | 用途                              |
| :----------- | :---- | :-------------------------------- |
| Spring Boot  | 3.5.3 | 应用框架                          |
| Apache Spark | 4.0.0 | 实时流处理 (Structured Streaming) |
| Apache Kafka | 3.7.0 | 消息队列                          |
| MySQL        | 8.x   | 行为聚合持久化                    |
| Java         | 17+   | 运行时                            |
| Scala        | 2.13  | Spark 依赖                        |
| Lombok       | -     | 代码简化                          |
| Jackson      | -     | JSON 序列化                       |

---

## 快速启动

### 环境要求

- JDK 17+
- Maven 3.9+
- Kafka（`localhost:9092`）
- MySQL（`localhost:3306`）

### 1 数据库准备

```sql
CREATE DATABASE IF NOT EXISTS recommendation;
USE recommendation;

CREATE TABLE user_behavior_aggregation (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    behavior_type   VARCHAR(32)  NOT NULL,
    window_start    DATETIME(3)  NOT NULL,
    window_end      DATETIME(3)  NOT NULL,
    event_count     BIGINT       NOT NULL,
    avg_rating      DOUBLE,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_user_behavior_window (user_id, behavior_type, window_start, window_end),
    INDEX idx_user_window (user_id, window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 2 Kafka 准备

```bash
# 确认 Kafka 可用
kafka-topics.sh --bootstrap-server localhost:9092 --list

# Topic 会由应用自动创建（spring.kafka.admin.auto-create=true）
# 如需手动创建：
kafka-topics.sh --create --topic user-events --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1
kafka-topics.sh --create --topic recommendations --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1
```

### 3 启动应用

```bash
cd backed
mvn clean package -DskipTests
mvn spring-boot:run
```

应用监听 `http://localhost:8922`。

### 4 启动流处理 + 模拟器

```bash
# 启动，每秒生成 5 条事件
curl -X POST "http://localhost:8922/api/stream/start?eventsPerSecond=5"
```

PowerShell:

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:8922/api/stream/start?eventsPerSecond=5'
```

### 5 查看状态

```bash
curl http://localhost:8922/api/stream/status
```

### 6 手动发送事件

```bash
curl -X POST "http://localhost:8922/api/stream/event" \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-1001",
    "userId": 1,
    "itemId": 101,
    "behaviorType": "CLICK",
    "sessionId": "sess-debug",
    "deviceInfo": "Web-Chrome",
    "timestamp": "2026-03-16T10:00:00Z"
  }'
```

### 7 停止

```bash
curl -X POST "http://localhost:8922/api/stream/stop"
```

---

## 观测结果

启动后观察控制台日志：

1. **Kafka 生产日志**：`[Kafka] 发送成功 userId=... partition=... offset=...`
2. **聚合写入日志**：`[Spark] batchId=... aggregation upserted to MySQL`
3. **推荐输出日志**：`[Spark] batchId=... recommendations written to topic=recommendations`

查询 MySQL 验证聚合结果：

```sql
SELECT user_id, behavior_type, event_count, avg_rating, window_start, window_end
FROM user_behavior_aggregation
ORDER BY window_start DESC
LIMIT 20;
```

消费 Kafka 验证推荐结果：

```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic recommendations --from-beginning
```

---

## 配置说明

配置文件：`backed/src/main/resources/application.yml`

| 配置项                                   | 默认值                                     | 说明            |
| :--------------------------------------- | :----------------------------------------- | :-------------- |
| `server.port`                            | 8922                                       | 服务端口        |
| `spring.kafka.bootstrap-servers`         | localhost:9092                             | Kafka 地址      |
| `spring.datasource.url`                  | jdbc:mysql://localhost:3306/recommendation | MySQL 地址      |
| `spark.master`                           | local[*]                                   | Spark 运行模式  |
| `spark.sql.streaming.checkpointLocation` | ./spark-checkpoint                         | Checkpoint 目录 |
| `recommendation.top-n`                   | 10                                         | 每用户推荐数量  |
| `kafka.topic.user-events`                | user-events                                | 行为事件 Topic  |
| `kafka.topic.recommendations`            | recommendations                            | 推荐结果 Topic  |

生产环境覆盖：`application-prod.yml`

---

## 常见问题

1. **Kafka 连接失败** -- 确认 `localhost:9092` 可达，用 `kafka-topics.sh --list` 验证
2. **启动后无 Spark 日志** -- 确认已调用 `POST /api/stream/start`，检查日志中是否有 `Streaming Query` 关键词
3. **MySQL 写入失败** -- 确认数据库 `recommendation` 已创建，表 `user_behavior_aggregation` 已建好
4. **foreachBatch 编译歧义** -- Spark 4.0 的 Java lambda 需要显式强转 `(VoidFunction2<Dataset<Row>, Long>)`
