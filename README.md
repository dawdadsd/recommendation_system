# Recommendation System

这是一个用于学习、演示和逐步扩展的推荐系统工程，当前包含 3 个模块：

- `backed`：在线服务模块
- `recommendation-trainer`：离线训练模块
- `example`：教学示例模块

技术栈：

- `Java 17`
- `Spring Boot 3.5`
- `Kafka`
- `Spark 4.0`
- `MySQL`
- `Spring AI`

## 模块概览

### `backed`

在线服务，主要负责：

- Web API 暴露
- Kafka 事件接入
- Spark Streaming 行为流处理
- 用户偏好聚合
- 在线召回读取
- AI 对话接口

默认端口：

- `8922`

### `recommendation-trainer`

离线训练服务，主要负责：

- 基于 `user_item_preference` 做 ALS 训练
- 生成 `user_cf_recall`
- 发布 `recommendation_model_version`
- 清理旧版本召回数据

默认端口：

- `8923`

### `example`

教学示例模块，专门放“可运行、可观察、可扩展”的学习型代码。

默认端口：

- `8924`

## `example` 目前有什么 example

当前 `example` 模块里有 2 个可运行示例：

### 1. JVM 内存与 GC 学习示例

位置：

- [JvmMemoryLearningService.java](c:\Users\admin\Desktop\recommendation_system\example\src\main\java\xiaowu\example\jvm\application\service\JvmMemoryLearningService.java)
- [JvmMemoryLearningController.java](c:\Users\admin\Desktop\recommendation_system\example\src\main\java\xiaowu\example\jvm\interfaces\rest\JvmMemoryLearningController.java)

这个示例能学什么：

- JVM 内存划分
- Heap / Non-Heap / Direct Buffer 的基本观察
- GC Roots
- 可达性分析
- 标记清理的基本过程
- 为什么纯 mark-sweep 会有碎片问题

可访问接口：

- `GET /api/examples/jvm/layout`
- `GET /api/examples/jvm/gc/mark-sweep`
- `GET /api/examples/jvm/overview`

### 2. 支付幂等教学示例

位置：

- [PaymentOrder.java](c:\Users\admin\Desktop\recommendation_system\example\src\main\java\xiaowu\example\payment\domain\entity\PaymentOrder.java)
- [PaymentOrderRepository.java](c:\Users\admin\Desktop\recommendation_system\example\src\main\java\xiaowu\example\payment\domain\repository\PaymentOrderRepository.java)
- [JdbcPaymentOrderRepository.java](c:\Users\admin\Desktop\recommendation_system\example\src\main\java\xiaowu\example\payment\infrastructure\persistence\JdbcPaymentOrderRepository.java)
- [PaymentApplicationService.java](c:\Users\admin\Desktop\recommendation_system\example\src\main\java\xiaowu\example\payment\application\service\PaymentApplicationService.java)
- [PaymentController.java](c:\Users\admin\Desktop\recommendation_system\example\src\main\java\xiaowu\example\payment\interfaces\rest\PaymentController.java)

这个示例能学什么：

- 为什么支付必须有幂等键
- 为什么状态机必须是单向流转
- 为什么 `UNPAID -> PAYING -> SUCCESS / CLOSED` 比“只有成功/失败”更真实
- 为什么真正的幂等护栏最终要落在数据库条件更新上
- 如何用 `Repository + ApplicationService + Controller` 搭一个最小可运行支付链

当前已实现的能力：

- 创建订单
- 幂等创建
- 发起支付
- 回写支付成功
- 关闭订单
- 查询订单状态

可访问接口：

- `GET /api/examples/payments/demo`
- `POST /api/examples/payments/orders`
- `POST /api/examples/payments/orders/{orderNo}/start`
- `POST /api/examples/payments/orders/{orderNo}/success`
- `POST /api/examples/payments/orders/{orderNo}/close`
- `GET /api/examples/payments/orders/{orderNo}`

## 项目结构

```text
recommendation_system/
├─ pom.xml
├─ backed/
├─ recommendation-trainer/
├─ example/
└─ docs/
```

## 启动方式

### 启动在线服务

```bash
mvn -pl backed -DskipTests spring-boot:run
```

### 启动离线训练服务

```bash
mvn -pl recommendation-trainer -DskipTests spring-boot:run
```

### 启动教学示例模块

```bash
mvn -pl example -DskipTests spring-boot:run
```

## 数据准备

### `backed` 和 `recommendation-trainer`

这两个模块默认依赖 MySQL。

可以先启动本地 MySQL：

```bash
docker run -d \
  --name mysql \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=recommendation \
  --restart unless-stopped \
  mysql:8.4
```

相关 SQL 目录：

- `backed/src/main/resources/database/`
- `recommendation-trainer/src/main/resources/database/`

### `example`

`example` 模块默认使用 H2 内存数据库，不需要手工建库。

启动时会自动执行：

- [schema.sql](c:\Users\admin\Desktop\recommendation_system\example\src\main\resources\schema.sql)
- [data.sql](c:\Users\admin\Desktop\recommendation_system\example\src\main\resources\data.sql)

H2 控制台地址：

- `http://localhost:8924/h2-console`

JDBC URL：

- `jdbc:h2:mem:exampledb`

## 支付示例测试顺序

### 1. 查看内置测试样例

```http
GET /api/examples/payments/demo
```

### 2. 创建订单

```http
POST /api/examples/payments/orders
Content-Type: application/json
```

```json
{
  "orderNo": "PAY_NEW_20260320_001",
  "idempotencyKey": "IDEMP_NEW_20260320_001",
  "userId": 1001,
  "productCode": "VIP_MONTH",
  "amountFen": 9900
}
```

### 3. 发起支付

```http
POST /api/examples/payments/orders/PAY_NEW_20260320_001/start
Content-Type: application/json
```

```json
{
  "idempotencyKey": "IDEMP_NEW_20260320_001"
}
```

### 4. 模拟支付成功回调

```http
POST /api/examples/payments/orders/PAY_NEW_20260320_001/success
Content-Type: application/json
```

```json
{
  "channelTradeNo": "WX202603200099",
  "paidAt": "2026-03-20T16:30:00"
}
```

### 5. 查询订单状态

```http
GET /api/examples/payments/orders/PAY_NEW_20260320_001
```

## 支付示例内置种子数据

`data.sql` 已经预置 3 笔订单，方便直接测试：

- `PAY_DEMO_SUCCESS_001`
- `PAY_DEMO_PAYING_001`
- `PAY_DEMO_CLOSED_001`

它们分别用于观察：

- 已成功订单的幂等回写
- 支付中订单的查询与补偿入口
- 已关闭订单的非法流转拦截

## 当前下一步扩展方向

`example` 模块接下来最自然的扩展是：

- 接入 `Kafka`，模拟支付成功消息重复投递
- 接入 `Redisson`，演示按订单号加分布式锁
- 增加“支付中超时扫描”的补偿任务
- 进一步贴近真实支付系统的幂等闭环
