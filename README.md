# recommendation_system

一个基于 **Spring Boot + Kafka + Spark Structured Streaming** 的实时行为模拟演示项目。

该版本目前以“可观测的行为流管道”为主：

- REST 接口可手动发送事件
- 内置模拟器可持续生成用户行为事件
- Spark Streaming 持续消费 Kafka 并做 30 秒窗口聚合

## 一、项目结构

- `backed/src/main/java/xiaowu/backed/Application.java`
  - Spring Boot 启动入口
- `backed/src/main/java/xiaowu/backed/interfaces/rest/StreamController.java`
  - 提供 Demo 用的操作接口：`start / stop / status / event`
- `backed/src/main/java/xiaowu/backed/application/service/StreamSimulatorService.java`
  - 定时生成随机行为事件（`VIEW/CLICK/ADD_TO_CART/PURCHASE/RATE`）并发送到 Kafka
- `backed/src/main/java/xiaowu/backed/application/dto/BehaviorEventDTO.java`
  - 事件对象定义（`userId`、`itemId`、`behaviorType`、`rating` 等）
- `backed/src/main/java/xiaowu/backed/infrastructure/kafka/BehaviorEventProducer.java`
  - 将事件序列化为 JSON 并异步发往 Kafka
- `backed/src/main/java/xiaowu/backed/infrastructure/spark/BehaviorStreamProcessor.java`
  - 从 `user-events` 订阅 Kafka 并进行结构化流式聚合

## 二、快速启动 Demo

### 1) 环境要求

- JDK：建议 `21+`
- Maven：3.9+
- Kafka：本地可用的 `localhost:9092`
- curl 或 PowerShell/HTTP Client 可用（用于调用 REST API）

### 2) Kafka 准备

确认 Kafka 已启动并可访问：

```bash
# Linux / macOS
kafka-topics.sh --bootstrap-server localhost:9092 --list

# Windows
kafka-topics.bat --bootstrap-server localhost:9092 --list
```

创建主题（如未开启 auto-create）

```bash
kafka-topics.sh --create --topic user-events --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1
```

可选：若需观察窗口结果，建议再建一个空主题（代码当前未消费）：

```bash
kafka-topics.sh --create --topic recommendations --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1
```

### 3) 启动 Spring Boot

```bash
cd backed
mvn clean package -DskipTests
mvn spring-boot:run
```

应用启动后默认监听 `http://localhost:8080`。

### 4) 启动 Spark 流处理与模拟器（核心 Demo）

```bash
# 每秒 5 条事件
default_rate=5
curl -X POST "http://localhost:8080/api/stream/start?eventsPerSecond=5"
```

如果你在 Windows PowerShell 中操作：

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/stream/start?eventsPerSecond=5'
```

该接口会做两件事：

1. 启动 Spark Streaming 查询
2. 启动随机事件模拟器

### 5) 查看运行状态

```bash
curl -X GET "http://localhost:8080/api/stream/status"
```

### 6) 手动发送一条事件

```bash
curl -X POST "http://localhost:8080/api/stream/event" \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-1001","userId":1,"itemId":101,"behaviorType":"CLICK","sessionId":"sess-debug","deviceInfo":"Web-Chrome","timestamp":"2026-03-02T10:00:00Z"}'
```

`RATE` 行为可带评分：

```bash
curl -X POST "http://localhost:8080/api/stream/event" \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-1002","userId":2,"itemId":88,"behaviorType":"RATE","rating":4.5,"sessionId":"sess-debug","deviceInfo":"iOS-iPhone15","timestamp":"2026-03-02T10:00:01Z"}'
```

### 7) 停止模拟

```bash
curl -X POST "http://localhost:8080/api/stream/stop"
```

## 三、Demo 结果怎么判断

启动后可在控制台看到两类关键日志：

1. 生产日志（Kafka Producer）
   - 成功发送：`[Kafka] 发送成功 userId=... partition=... offset=...`
   - 失败发送：`[Kafka] 发送失败 ...`

2. Spark 聚合输出（`console`）
   - 每 10 秒输出一次最近 30 秒窗口的聚合：
     - `windowStart`, `windowEnd`
     - `userId`, `behaviorType`
     - `eventCount`, `avgRating`

## 四、接口清单

- `POST /api/stream/start?eventsPerSecond=<n>`
  - 启动（或重置）模拟器流。
- `POST /api/stream/stop`
  - 停止模拟器与 Spark 查询。
- `GET /api/stream/status`
  - 查询服务端当前状态。
- `POST /api/stream/event`
  - 手动投递单条行为事件。

## 五、配置文件

默认配置位于：`backed/src/main/resources/application.properties`

关键字段：

- `spring.kafka.bootstrap-servers`
  - Kafka 地址
- `kafka.topic.user-events`
  - 行为事件主题（默认 `user-events`）
- `spark.master`
  - 默认 `local[*]`
- `spark.sql.streaming.checkpointLocation`
  - 默认 `./spark-checkpoint`

## 六、常见问题排查

1. 启动时报 `Kafka` 连接失败
   - 确认 `localhost:9092` 可达
   - 用 `kafka-topics.bat --bootstrap-server localhost:9092 --list` 验证
2. 启动成功但无 Spark 日志
   - 先确认 `start` 已调用并返回 `sparkRunning=true`
   - 检查应用日志是否有 `Streaming Query` 关键词
3. 发送事件无响应但不报错
   - 检查 JSON 字段是否符合模型（`userId`/`itemId` 为数字、`behaviorType` 为指定枚举之一）
