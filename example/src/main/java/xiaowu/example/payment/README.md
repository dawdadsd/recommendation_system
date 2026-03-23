# payment 模块说明

这个模块现在承担两条教学主线：

1. 支付幂等与支付状态机
2. 秒杀预占库存的本地教学骨架

它不是生产级完整系统，而是一个方便你边读代码、边跑接口、边理解设计取舍的学习模块。

---

## 1. 当前已经实现了什么

### 1.1 支付教学链路

这一部分已经可以直接运行和调接口，核心目标是讲清楚：

- 为什么支付请求必须幂等
- 为什么支付状态必须单向流转
- 为什么支付成功和关单都要带旧状态条件更新

当前支付部分的核心类：

- `domain/entity/PaymentOrder.java`
- `application/service/PaymentApplicationService.java`
- `domain/repository/PaymentOrderRepository.java`
- `infrastructure/persistence/JdbcPaymentOrderRepository.java`
- `interfaces/rest/PaymentController.java`

支付状态机是：

```text
UNPAID -> PAYING -> SUCCESS / CLOSED
```

---

### 1.2 秒杀教学骨架

这一部分目前先落了数据库真相层，还没有接 Redis 和 Kafka。

也就是说，现在你已经可以在代码里看清楚这些概念：

- 秒杀里抢到的不是最终成交资格，而是限时预占资格
- 库存应该拆成 `available / reserved / sold`
- 资格状态和支付状态要分层建模
- Redis 和 Kafka 以后接进来时，应该接在哪一层

当前已经补上的秒杀代码：

- `seckill/domain/entity/SeckillReservation.java`
- `seckill/domain/entity/SeckillStock.java`
- `seckill/domain/repository/SeckillReservationRepository.java`
- `seckill/domain/repository/SeckillStockRepository.java`
- `seckill/infrastructure/persistence/JdbcSeckillReservationRepository.java`
- `seckill/infrastructure/persistence/JdbcSeckillStockRepository.java`

当前秒杀部分的重点不是接口，而是两张表和两类状态推进：

- `seckill_reservation`
- `seckill_stock`

---

## 2. 为什么秒杀先只做 JDBC + H2

这是刻意的，不是没做完就先丢着。

原因是：

1. 秒杀最容易讲乱的不是 Redis 命令怎么写，而是库存真相和资格状态到底该怎么落库。
2. 如果数据库状态机都没定住，就先接 Redis 和 Kafka，最后通常会变成“消息会飞，但业务边界不清楚”。
3. 先把 H2 + JDBC 跑通后，后面再接 Redis Lua 和 Kafka，只是在现有边界上补适配器，不用返工整个模型。

所以当前阶段的目标是先把下面这件事定死：

```text
可售库存 -> 冻结库存 -> 已售库存 / 已释放库存
```

---

## 3. 当前秒杀状态设计

### 3.1 资格状态

当前代码里的秒杀资格状态在：

- `SeckillReservation.ReservationStatus`

当前保留的状态有：

```text
RESERVED
ORDER_CREATED
PAID
CANCELLED
EXPIRED
RELEASED
```

其中最关键的是：

- `PAID` 之后绝不能释放库存
- `RELEASED` 是释放终态，同一条资格只能进入一次

---

### 3.2 库存状态

当前库存模型在：

- `SeckillStock`

库存恒等式是：

```text
available_stock + reserved_stock + sold_stock = total_stock
```

三种核心推进：

1. 用户抢到资格

```text
available - 1
reserved + 1
```

2. 用户支付成功

```text
reserved - 1
sold + 1
```

3. 用户取消或超时

```text
reserved - 1
available + 1
```

---

## 4. 当前数据库表

当前 H2 中已经有以下几张核心表：

- `payment_order`
- `payment_demo_user`
- `payment_demo_product`
- `seckill_stock`
- `seckill_reservation`

其中新增的两张秒杀表定义在：

- `src/main/resources/schema.sql`

演示数据在：

- `src/main/resources/data.sql`

你现在可以在 H2 控制台里直接查这两张表，看秒杀资格和库存真相长什么样。

---

## 5. 如何运行

启动：

```bash
mvn -pl example -DskipTests spring-boot:run
```

默认地址：

- `http://localhost:8924`

H2 控制台：

- `http://localhost:8924/h2-console`
- JDBC URL: `jdbc:h2:mem:exampledb`
- user: `sa`
- password: 空

如果 `8924` 端口被占用，可以临时换端口启动。

---

## 6. 支付接口还能怎么测

当前可直接调的还是支付教学接口：

1. 查看 demo 数据

```http
GET /api/examples/payments/demo
```

2. 创建订单

```http
POST /api/examples/payments/orders
```

3. 发起支付

```http
POST /api/examples/payments/orders/{orderNo}/start
```

4. 模拟支付成功

```http
POST /api/examples/payments/orders/{orderNo}/success
```

5. 关闭订单

```http
POST /api/examples/payments/orders/{orderNo}/close
```

6. 查询订单

```http
GET /api/examples/payments/orders/{orderNo}
```

更细的操作样例可以看：

- `doc/测试教学.md`

---

## 7. 秒杀部分现在怎么学

虽然秒杀还没开放 REST 接口，但你已经可以按下面顺序读：

1. 先看设计文档

- `doc/秒杀教学-Redis-Kafka方案.md`

2. 再看领域对象

- `seckill/domain/entity/SeckillReservation.java`
- `seckill/domain/entity/SeckillStock.java`

3. 再看仓储接口

- `seckill/domain/repository/SeckillReservationRepository.java`
- `seckill/domain/repository/SeckillStockRepository.java`

4. 最后看 JDBC 实现

- `seckill/infrastructure/persistence/JdbcSeckillReservationRepository.java`
- `seckill/infrastructure/persistence/JdbcSeckillStockRepository.java`

建议你重点观察两件事：

- 为什么仓储更新要带 `expectedStatus` 或 `version`
- 为什么释放库存不能只是简单 `INCR`

---

## 8. 当前还没做的部分

这部分暂时故意不接：

- Redis Lua 原子预占
- Kafka 异步建单
- Redis ZSet 超时释放
- 秒杀 REST Controller
- 秒杀 ApplicationService 的完整 Spring 装配

原因不是不需要，而是当前阶段优先级更低。

你现在回头接 Redis 和 Kafka 时，应该把它们分别接到这里：

- Redis 对接 `ReservationCacheGateway`
- Kafka 对接 `ReservationEventPublisher`

---

## 9. 下一步建议

当前最合理的继续顺序是：

1. 先补一个本地秒杀应用服务，把 JDBC 仓储真正串起来
2. 再补一个简单的秒杀查询/演示接口
3. 最后再接 Redis 和 Kafka 适配器

这样做的好处是：每一步都能验证业务边界，没有哪一层是“先接了再说”。

---

## 10. 一句话总结

这个模块当前已经把两件关键事情分开了：

- `payment_order` 负责支付真相
- `seckill_reservation + seckill_stock` 负责秒杀库存真相

后面不管你接 Redis 还是 Kafka，都应该围绕这个分层继续往下长，而不是把所有状态重新揉成一团。
