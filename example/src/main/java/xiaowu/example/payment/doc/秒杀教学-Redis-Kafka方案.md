# Redis + Kafka 秒杀教学设计

## 1. 目标

这份设计不是为了把当前 `payment` 模块直接改造成生产秒杀系统，而是为了在你现有工程里，先把下面这件事讲清楚：

- 秒杀里抢到的通常不是最终成交资格，而是限时预占资格
- Redis 扣减的是可售库存，不是最终卖出库存
- Kafka 负责把抢购流量削峰成可控的异步下单流
- MySQL 负责保存库存和订单真相
- 超时释放不要指望 Kafka 原生延迟，要靠 Redis ZSet 或定时扫描

当前模块里已经有一个很好的支付状态机起点：

- `PaymentOrder`
- `PaymentApplicationService`
- `PaymentOrderRepository`

这套代码已经把 `UNPAID -> PAYING -> SUCCESS / CLOSED` 的支付流转定住了。

秒杀要补上的，不是再造一套支付模型，而是补一层“预占资格”模型。

---

## 2. 技术决策

### 2.1 结论

在这个仓库里，我建议秒杀链路选：

`Redis + Kafka + MySQL + Redis ZSet`

### 2.2 为什么不是 RabbitMQ

RabbitMQ 的优势是延迟消息和路由模型更直接，做“超时关单”会更顺手。

但放到你这个仓库里，Kafka 更合适，原因是：

1. 仓库里已经有 Kafka 基础设施和依赖，`backed` 模块已在使用 Kafka。
2. 推荐系统天然存在事件流、审计流、异步通知、多消费者订阅这些需求，Kafka 更统一。
3. 秒杀里的 MQ 主要职责是削峰和异步化，而不是复杂路由，Kafka 足够胜任。

### 2.3 约束

Kafka 在这里要负责：

- `reservation created` 事件
- `reservation released` 事件
- 审计和对账事件

Kafka 不建议直接负责：

- 订单超时延迟触发

原因很简单：

- Kafka 原生不擅长精确延迟投递
- 为了一个超时关单强行绕 Kafka，会把系统讲复杂

所以超时释放建议用：

- Redis ZSet
- 或定时任务扫描 MySQL

---

## 3. 当前模块与秒杀的边界

### 3.1 当前已有能力

当前 `payment` 模块已经具备：

- 支付订单聚合根
- 支付状态机
- 幂等创建订单
- 条件更新防并发状态穿透

### 3.2 当前缺失能力

秒杀真正缺的是：

- 预占库存模型
- 资格状态机
- Redis 原子预占入口
- Kafka 异步建单入口
- 超时释放库存入口

### 3.3 推荐边界划分

建议把边界拆成两层：

1. 秒杀预占层
   - 负责抢资格
   - 负责可售库存和冻结库存的流转
   - 负责资格取消、超时释放

2. 支付订单层
   - 负责支付状态流转
   - 负责支付成功、支付关闭
   - 负责最终支付真相

这意味着：

- `seckill_reservation` 是秒杀聚合
- `payment_order` 继续作为支付聚合

不要把“秒杀资格状态”和“支付订单状态”混在一个枚举里。

---

## 4. 数据模型

## 4.1 库存真相表

```sql
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
```

恒等式必须成立：

```text
available_stock + reserved_stock + sold_stock = total_stock
```

## 4.2 资格表

```sql
CREATE TABLE seckill_reservation (
    reservation_id         VARCHAR(64)  PRIMARY KEY,
    activity_id            BIGINT       NOT NULL,
    sku_id                 BIGINT       NOT NULL,
    user_id                BIGINT       NOT NULL,
    reservation_token      VARCHAR(64)  NOT NULL,
    status                 VARCHAR(32)  NOT NULL,
    payment_order_no       VARCHAR(64),
    expire_at              TIMESTAMP    NOT NULL,
    released_at            TIMESTAMP,
    created_at             TIMESTAMP    NOT NULL,
    updated_at             TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX uk_seckill_reservation_user
    ON seckill_reservation (activity_id, sku_id, user_id);
```

说明：

- `reservation_id` 是资格唯一标识，也是后续 Kafka 消息主键
- `reservation_token` 可以理解成“预占幂等键”
- 同一用户同一活动同一 SKU 只允许一条有效资格

## 4.3 支付表

这里尽量复用现有的 `payment_order`。

建议做法：

- 秒杀消费者创建资格后，调用支付应用服务创建 `payment_order`
- `payment_order.order_no` 可以用秒杀订单号或业务订单号
- `payment_order` 不负责库存，只负责支付

---

## 5. 状态机

## 5.1 资格状态机

推荐状态：

```text
NEW
-> RESERVED
-> ORDER_CREATING
-> ORDER_CREATED
-> PAID
-> RELEASED

分支：
RESERVED -> CANCELLED
ORDER_CREATED -> EXPIRED
ORDER_CREATED -> CANCELLED
CANCELLED / EXPIRED -> RELEASED
```

更收敛一点也可以这样定义：

```text
RESERVED
-> ORDER_CREATED
-> PAID

RESERVED -> CANCELLED -> RELEASED
ORDER_CREATED -> CANCELLED -> RELEASED
ORDER_CREATED -> EXPIRED -> RELEASED
```

关键约束：

1. `PAID` 之后绝不允许释放库存
2. `RELEASED` 是终态，必须保证只进入一次
3. 所有释放动作本质上都是状态迁移，不是单纯 `INCR`

## 5.2 支付状态机

继续沿用当前模块的设计：

```text
UNPAID -> PAYING -> SUCCESS / CLOSED
```

秒杀层不要改写这个状态机，只需要在合适时机调用它。

## 5.3 库存状态变化

```text
用户抢到资格:
available - 1
reserved + 1

支付成功:
reserved - 1
sold + 1

取消 / 超时:
reserved - 1
available + 1
```

---

## 6. Redis 设计

## 6.1 建议 Key

```text
seckill:stock:{skuId}:available
seckill:users:{activityId}:{skuId}
seckill:reservation:{reservationId}
seckill:release:zset
```

说明：

- `seckill:stock:{skuId}:available`
  - 保存 Redis 侧可售库存
- `seckill:users:{activityId}:{skuId}`
  - 保存已抢购用户去重标记
- `seckill:reservation:{reservationId}`
  - 保存资格快照，便于取消和补偿
- `seckill:release:zset`
  - 保存待超时释放的资格，score 为 `expireAt`

## 6.2 Lua 脚本职责

一次脚本里完成：

1. 判断活动是否开始
2. 判断用户是否重复抢购
3. 判断可售库存是否大于 0
4. 扣减可售库存
5. 写入用户去重标记
6. 写入资格快照
7. 把资格放进 `release:zset`

抢购成功后，前端拿到的响应应该是：

`抢购成功，已获得限时下单资格`

而不是：

`你已经买到了`

---

## 7. Kafka 设计

## 7.1 Topic 规划

推荐最少两个主题：

```text
seckill.reservation.created
seckill.reservation.release
```

可选扩展：

```text
seckill.reservation.audit
```

## 7.2 事件载荷

`seckill.reservation.created`

```json
{
  "reservationId": "RSV_20260323_0001",
  "activityId": 10001,
  "skuId": 20001,
  "userId": 30001,
  "expireAt": "2026-03-23T10:00:00",
  "occurredAt": "2026-03-23T09:55:00"
}
```

`seckill.reservation.release`

```json
{
  "reservationId": "RSV_20260323_0001",
  "reason": "USER_CANCELLED",
  "occurredAt": "2026-03-23T09:56:10"
}
```

## 7.3 分区键建议

优先使用：

`reservationId`

原因：

- 秒杀热点 SKU 如果强行按 `skuId` 分区，容易把一个热点全部压到单分区
- 库存正确性已经由 Redis 原子脚本和 DB 条件更新兜底
- Kafka 这里的首要目标是异步削峰，不是靠分区顺序保证库存正确

---

## 8. 端到端流程

## 8.1 用户抢购

```text
Client
  -> Redis Lua reserve
  -> Kafka send reservation.created
  -> return reserved
```

## 8.2 Kafka 消费建单

```text
Kafka consumer
  -> load reservation from DB/Redis snapshot
  -> check reservation status is still valid
  -> begin transaction
       1. insert / update seckill_reservation
       2. update seckill_stock:
          available_stock - 1
          reserved_stock + 1
       3. create payment_order
     commit
```

注意：

- 消费消息不是拿到就盲目建单
- 必须先检查这条资格是否已经取消

## 8.3 用户取消

分两种情况：

### 情况 A：Redis 预占成功，但 DB 订单还没创建

动作：

1. 标记资格为 `CANCELLED`
2. 释放 Redis 可售库存
3. 从用户去重集合移除标记
4. 后续 Kafka 消费到该消息时，先检查状态，发现已取消则直接跳过

### 情况 B：DB 订单已创建，但未支付

动作：

1. 关闭支付订单
2. 把资格从 `ORDER_CREATED` 改为 `CANCELLED`
3. 释放 DB 冻结库存
4. 回补 Redis 可售库存

## 8.4 超时释放

推荐流程：

```text
scheduler / release worker
  -> scan Redis ZSet where score <= now
  -> try move reservation status to EXPIRED
  -> close payment order if exists
  -> release DB reserved stock
  -> restore Redis available stock
```

---

## 9. 幂等与并发控制

## 9.1 释放库存必须幂等

这是整条链路最容易被追问的点。

可能同时触发释放的入口有：

- 用户主动取消
- 支付超时任务
- Kafka 补偿消息
- 人工补偿脚本

如果这些入口都直接 `INCR Redis`，库存会被重复释放，最终超卖。

正确做法是：

先改状态，再看是否允许释放。

只有下面这些状态才能释放：

- `RESERVED`
- `ORDER_CREATED`
- `CANCELLED`
- `EXPIRED`

一旦成功释放，资格状态改为：

- `RELEASED`

后续重复释放请求必须直接忽略。

## 9.2 DB 条件更新示例

关闭支付订单：

```sql
UPDATE payment_order
SET status = 'CLOSED',
    closed_at = ?,
    updated_at = ?
WHERE order_no = ?
  AND status = 'UNPAID';
```

释放库存：

```sql
UPDATE seckill_stock
SET reserved_stock = reserved_stock - 1,
    available_stock = available_stock + 1,
    version = version + 1,
    updated_at = ?
WHERE sku_id = ?
  AND reserved_stock > 0
  AND version = ?;
```

影响行数为 0 的语义：

- 可能已经支付成功
- 可能已经被其他线程释放
- 当前线程必须停止后续释放动作

---

## 10. 风险清单

| 风险 | 影响 | 概率 | 处理方式 |
| --- | --- | --- | --- |
| Redis 已预占但 Kafka 发送失败 | 高 | 中 | 本地补偿日志或事务消息/outbox，失败则回滚 Redis 预占 |
| Kafka 重复消费导致重复建单 | 高 | 中 | `reservationId` 幂等校验，DB 唯一约束，状态机条件更新 |
| 用户取消与超时任务重复释放 | 高 | 高 | 以 `RELEASED` 作为唯一释放终态 |
| Redis 与 MySQL 库存短暂不一致 | 中 | 高 | 定时对账，以 DB 为最终真相修正 Redis |
| 热点 SKU 打爆单分区 | 中 | 中 | Kafka key 用 `reservationId`，库存正确性不依赖分区顺序 |
| 延迟关单依赖 Kafka 变复杂 | 中 | 高 | 改为 Redis ZSet 或定时任务 |

---

## 11. 在当前项目里的推荐落地方式

为了尽量复用你现在的 `payment` 模块，我建议按三期走。

## 第一期：先补秒杀资格模型

新增包建议：

```text
xiaowu.example.payment.seckill.domain.entity
xiaowu.example.payment.seckill.domain.repository
xiaowu.example.payment.seckill.application.service
xiaowu.example.payment.seckill.interfaces.rest
xiaowu.example.payment.seckill.infrastructure.persistence
xiaowu.example.payment.seckill.infrastructure.redis
xiaowu.example.payment.seckill.infrastructure.kafka
```

核心类建议：

- `SeckillReservation`
- `SeckillStock`
- `SeckillReservationRepository`
- `SeckillStockRepository`
- `SeckillReservationApplicationService`

## 第二期：接 Redis 预占

新增端口：

- `ReservationCacheGateway`

Redis 适配器负责：

- Lua 原子预占
- 资格快照缓存
- 超时队列写入
- 回补可售库存

## 第三期：接 Kafka 建单

新增端口：

- `ReservationEventPublisher`

消费者负责：

1. 校验资格有效性
2. 更新 DB 库存真相
3. 调用现有支付应用服务创建 `payment_order`
4. 写入支付超时信息

---

## 12. 与当前 PaymentOrder 的衔接

推荐衔接方式：

1. 用户抢购成功，只产生 `reservation`
2. Kafka 消费建单时，调用当前 `PaymentApplicationService.createOrder(...)`
3. 订单支付成功后，再把秒杀资格从 `ORDER_CREATED` 推进到 `PAID`
4. 订单关闭时，再把资格推进到 `EXPIRED` 或 `CANCELLED`，并释放库存

这样分层的好处是：

- 秒杀资格状态不污染支付状态机
- 支付模块仍然可以单独教学和复用
- 面试时你能清晰说出“库存真相”和“支付真相”分别落在哪

---

## 13. 面试时怎么答为什么选 Kafka 不选 RabbitMQ

可以直接这样答：

> 在这个项目里我选 Redis + Kafka，而不是 Redis + RabbitMQ。核心原因不是 RabbitMQ 做不了，而是这个仓库本身已经有 Kafka 基础设施，推荐系统也天然存在事件流、多消费者订阅和审计链路，统一到 Kafka 更符合整体技术栈。  
> 但我不会用 Kafka 强行做延迟关单，因为 Kafka 原生不擅长精确延迟投递。秒杀里的超时释放我会交给 Redis ZSet 或定时扫描处理。  
> 所以最终分工是 Redis 做原子预占和回补，Kafka 做异步削峰和事件传播，MySQL 做库存与订单真相，超时释放走 Redis ZSet 或定时任务。

---

## 14. 下一步最值得先做什么

如果现在开始写代码，我建议顺序固定成下面这样：

1. 先写 `SeckillReservation` 状态机
2. 再写 `seckill_reservation` 和 `seckill_stock` 的 JDBC Repository
3. 再补一个不接 Redis/Kafka 的本地教学版 Service，把状态流先跑通
4. 最后再把 Redis 和 Kafka 适配器接进来

原因：

- 先把状态机和数据库真相定住，后面 Redis/Kafka 才有接入边界
- 如果一开始就先接中间件，最后往往变成“消息会飞，但业务边界还是乱的”

---

## 15. 一句话总结

秒杀系统的关键不是“用哪个 MQ”，而是你有没有把库存设计成：

`可售 -> 预占 -> 已售 / 已释放`

在这个仓库里，最稳的教学路线是：

`Redis 预占 + Kafka 建单 + MySQL 真相 + Redis ZSet 超时释放`
