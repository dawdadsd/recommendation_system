## ● 真实场景：小型电商小程序 — 日活1000-5000

### 背景设定

- **公司：** 做垂直品类的电商，比如母婴/宠物/农产品
- **主要入口：** 微信小程序（占90%流量），Web端是管理后台
- **团队规模：** 后端3人 + 前端2人 + 你（实习生）
- **日活：** 1000-5000人
- **日均订单：** 200-800单
- **技术栈：** Spring Boot + MySQL + Redis + 微信小程序

**你的leader第一天：**

> "我们现在所有东西都是同步调用，耦合太严重了。下单的时候要扣库存、创建订单、通知商家、给用户发微信消息、记积分，全写在一个方法里，600多行。你用Kafka把下单后的通知和异步操作拆出去。"

你先看了现有代码，发现了这个怪物方法：

```java
// 改造前：leader让你看的代码
@Transactional
public OrderResult createOrder(OrderCreateDTO dto) {
    // 1. 校验库存
    Product product = productMapper.selectById(dto.getProductId());
    if (product.getStock() < dto.getQuantity()) {
        throw new BizException("库存不足");
    }

    // 2. 扣库存
    productMapper.decreaseStock(dto.getProductId(), dto.getQuantity());

    // 3. 创建订单
    Order order = buildOrder(dto);
    orderMapper.insert(order);

    // 4. 发微信订阅消息通知用户（同步HTTP调用微信API）
    wechatService.sendSubscribeMessage(order);  // 耗时200-500ms

    // 5. 通知商家（调商家端的WebSocket推送）
    merchantNotifyService.notifyNewOrder(order);  // 耗时50ms

    // 6. 记积分
    pointService.addPoints(order.getUserId(), order.getAmount());  // 耗时30ms

    // 7. 生成物流预约单
    logisticsService.createShipment(order);  // 耗时100-300ms

    // 8. 更新用户消费统计
    userStatService.updateSpending(order.getUserId(), order.getAmount());

    return OrderResult.success(order.getId());
}
// 用户点"立即购买"到看到"下单成功"，要等1-2秒
// 微信API偶尔超时3秒，用户以为下单失败又点一次

```

### 改造后的架构

**改造前（同步，1-2秒）：**

```text
小程序 → 下单接口 → 扣库存 → 建订单 → 发微信消息 → 通知商家 → 记积分 → ... → 返回
                                         全部串行等待

```

**改造后（核心同步 + 异步解耦，200ms）：**

```text
小程序 → 下单接口 → 扣库存 → 建订单 → 发Kafka → 返回（200ms）
                                         ↓
                                order-created Topic
                                 ↓       ↓       ↓
                              消费者A    消费者B   消费者C
                            发微信消息  通知商家  记积分+统计+物流

```

---

### 问题1：这么小的量有必要用 Kafka 吗？

leader 让你先想这个问题，不要无脑上 Kafka。

你的日均订单：200-800单
峰值（晚8点促销）：可能50单/分钟

说实话，这个量用 Kafka 有点杀鸡用牛刀。Kafka集群本身要3台broker，运维成本不低。

**leader让你做的技术选型对比：**

- **方案A：Redis Stream（leader推荐）**
- → 你们已经有Redis了，零额外运维成本
- → 支持消费组、消息持久化、ack机制
- → 完全够用，日均800单Redis闭着眼处理
- → 缺点：没有Kafka的分区机制，扩展性弱

- **方案B：RocketMQ**
- → 阿里系，功能和Kafka类似
- → 支持延迟消息（订单30分钟未支付自动取消）
- → 但也要额外部署维护

- **方案C：Kafka**
- → 如果公司未来有实时数据分析、用户行为追踪的计划
- → 或者已经有Kafka集群（比如日志采集在用）
- → 那顺便用Kafka是合理的

**leader的最终决定：**

> "用Kafka。虽然现在量小，但我们下个季度要接数据分析平台，行为埋点也要走消息队列。统一用Kafka，不想维护两套。而且你学Kafka对你以后找工作有帮助。"（leader的真实原话风格）

**你面试时怎么讲：**

> "当时也考虑过Redis Stream，我们日均订单量不大。但leader从技术规划角度决定用Kafka，因为后续埋点数据和数据分析也要用，统一一套消息中间件运维成本更低。"

这句话面试官一听就知道你真正思考过技术选型，而不是简历写Kafka就无脑用Kafka。

---

### 问题2：消息发成功了但消费者没收到

你上线第一天遇到的：

- **现象：** 下单成功了，但商家端没收到通知，用户也没收到微信消息
- **日志：** Producer发送成功（有offset记录），但Consumer的日志里一条消费记录都没有

**你的排查过程：**

- **第1步：** 看Consumer有没有启动 → 日志显示启动了，没有报错
- **第2步：** 看Consumer的groupId和Topic对不对 → 对的
- **第3步：** 用命令查消费组状态
  `kafka-consumer-groups.sh --describe --group order-notify-group`
  _结果：_

```text
TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
（空的！没有任何分区分配记录）

```

- **第4步：** 看Consumer启动日志的细节 → 发现了：
  `"org.apache.kafka.clients.consumer.internals.AbstractCoordinator: [Consumer clientId=xxx, groupId=order-notify-group] Discovery of group coordinator failed"`
  → group coordinator连不上
- **第5步：** 检查网络
  `telnet kafka-broker 9092` → 通的
  但Consumer连的是 `bootstrap-servers=localhost:9092`
  而服务部署在Docker容器里，localhost不是宿主机！

**根因和修复：**

```yaml
# 错误：Docker容器里localhost指向容器自己
spring:
  kafka:
    bootstrap-servers: localhost:9092

# 正确：用Docker网络的服务名或宿主机IP
spring:
  kafka:
    bootstrap-servers: kafka:9092    # docker-compose的服务名
    # 或者：192.168.1.100:9092      # 宿主机IP

```

**你面试时怎么讲：**

> "上线第一天Consumer完全没消费。排查发现是Docker环境里bootstrap-servers写的localhost，容器里的localhost不是宿主机。改成Docker网络的服务名就好了。虽然是低级错误，但也让我养成了上线前先用kafka-consumer-groups命令确认分区分配的习惯。"

---

### 问题3：微信订阅消息发送频繁失败

上线一周后：

- **现象：** 用户投诉说下单了但没收到微信消息通知
- **日志：**
- `"errcode":43101,"errmsg":"user refuse to accept the msg"` → 正常，用户没授权
- `"errcode":45009,"errmsg":"reach max api daily quota limit"` → **有问题！**
- `"errcode":-1,"errmsg":"system error"` → 微信服务器偶尔抽风

**问题本质：**
微信订阅消息API有限制：

1. 每个用户每次只能授权1次发送机会
2. 接口有频率限制
3. 微信服务器偶尔500

你的消费者遇到失败直接跳过了，用户就收不到通知。

**leader让你做分级处理：**

```java
@KafkaListener(topics = "order-created", groupId = "wechat-notify-group")
public void sendWechatNotify(String message, Acknowledgment ack) {
    OrderEvent event = JSON.parseObject(message, OrderEvent.class);

    try {
        WechatResult result = wechatService.sendSubscribeMessage(
            event.getOpenId(), event.getOrderId(), event.getAmount());

        if (result.getErrCode() == 0) {
            // 发送成功
            ack.acknowledge();

        } else if (result.getErrCode() == 43101) {
            // 用户没授权，不是我们的问题，正常跳过
            log.info("用户未授权订阅消息: openId={}", event.getOpenId());
            ack.acknowledge();

        } else if (result.getErrCode() == 45009) {
            // 触发频率限制 → 不能跳过，要等一会重试
            log.warn("微信API限流，暂停消费60秒");
            Thread.sleep(60_000);
            // 不ack → 下次重新消费这条消息

        } else if (result.getErrCode() == -1) {
            // 微信服务器错误 → 重试
            log.warn("微信服务器错误，稍后重试");
            Thread.sleep(5_000);
            // 不ack → 重试
        }

    } catch (Exception e) {
        log.error("发送微信通知异常", e);
        // 网络超时等 → 转入重试Topic，不阻塞主流程
        kafkaTemplate.send("wechat-notify-retry", message);
        ack.acknowledge();
    }
}

```

- **leader追问你：** 微信限流时你 `Thread.sleep(60秒)`，这期间这个消费者线程完全阻塞了，其他消息也消费不了。有更好的方案吗？
- **你的改进：** 不要在消费线程里sleep，而是：

1. 把这条消息发到一个延迟Topic（delay-wechat-notify-60s）
2. 当前消息ack掉，继续消费下一条
3. 60秒后延迟Topic的消息到期，重新被消费

- 但你们没有用Kafka的延迟消息（Kafka原生不支持），所以leader说：简单做，用Redis的ZSET做延迟队列就行，没必要为了一个小功能把架构搞复杂。

---

### 问题4：促销活动下单量突增，消息堆积

**背景：**
公司做了一次微信群秒杀活动，晚8点开始。平时晚高峰：10单/分钟；秒杀开始后：200单/分钟（持续15分钟）。商家端通知延迟了5分钟，商家以为没有订单进来，打电话给运营投诉。

**你的排查：**

`kafka-consumer-groups.sh --describe --group merchant-notify-group`

```text
TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
order-created  0          12830           13891           1061

```

lag=1061，商家通知消费者跟不上了。

**原因分析：**
商家通知消费者里调了WebSocket推送 + 写了一条通知记录到MySQL。WebSocket推送很快（5ms），但MySQL写入在秒杀期间变慢了（50ms → 200ms），因为同一时间大量扣库存和建订单操作在抢MySQL连接。

**根因：不是Kafka的问题，是MySQL连接池被打满了。**

**你的解决方案：**

- // 方案1（你提的）：商家通知的MySQL写入改成异步批量。通知记录不需要实时写，攒10条写一次。
- // 方案2（leader让你做的，更简单）：通知记录走单独的数据源。主库处理订单和库存，从库处理通知记录的写入。连接池隔离，互不影响。
- // 方案3（最终方案）：通知记录先写Redis，定时同步到MySQL。商家通知这种场景，Redis写入1ms搞定，秒杀期间完全无压力。

```java
@KafkaListener(topics = "order-created", groupId = "merchant-notify-group")
public void notifyMerchant(String message, Acknowledgment ack) {
    OrderEvent event = JSON.parseObject(message, OrderEvent.class);

    // WebSocket推送（实时，给商家看）
    webSocketService.pushToMerchant(event.getMerchantId(), event);

    // 通知记录写Redis（快，不受MySQL连接池影响）
    String key = "merchant:notify:" + LocalDate.now();
    redisTemplate.opsForList().rightPush(key, message);
    redisTemplate.expire(key, 7, TimeUnit.DAYS);

    ack.acknowledge();
}

// 另一个定时任务：每5分钟把Redis里的通知记录批量同步到MySQL
@Scheduled(fixedRate = 300_000)
public void syncNotifyToMySQL() {
    // 从Redis批量读取 → 批量INSERT到MySQL
}

```

---

### 问题5：小程序消息模板和实际订单数据对不上

上线两周后，运营发现的：

- **运营：**"有个用户投诉说收到的微信消息里商品名是空的，订单金额显示0"
- **你查日志发现：**
  Producer发的消息：`{"orderId":10234, "productName":"有机纯牛奶1箱", "amount":89.9, ...}`
  Consumer收到的消息：`{"orderId":10234, "productName":"有机纯牛奶1箱", "amount":89.9, ...}`
  数据没问题啊？？
- **继续查，发现是另一条消息：**
  Producer发的：`{"orderId":10237, "productName":"", "amount":0, ...}`
  怎么会有空的？查订单表，10237的商品名和金额都有值。

**根因：**

```java
// 你写的发消息代码
@Transactional
public OrderResult createOrder(OrderCreateDTO dto) {
    productMapper.decreaseStock(dto.getProductId(), dto.getQuantity());

    Order order = new Order();
    order.setUserId(dto.getUserId());
    order.setProductId(dto.getProductId());
    // 注意：productName和amount在insert之后由数据库触发器/默认值填充
    orderMapper.insert(order);

    // 问题在这里！此时order对象里productName和amount还没有值
    // 因为insert后没有重新查询
    OrderEvent event = new OrderEvent();
    event.setOrderId(order.getId());
    event.setProductName(order.getProductName());  // null！
    event.setAmount(order.getAmount());            // 0！
    kafkaTemplate.send("order-created", JSON.toJSONString(event));

    // 后续代码里才设置了这些字段...
    order.setProductName(product.getName());
    order.setAmount(calculateAmount(dto));
    orderMapper.updateById(order);

    return OrderResult.success(order.getId());
}

```

**你的修复：**

```java
// 确保发消息时数据是完整的
@Transactional
public OrderResult createOrder(OrderCreateDTO dto) {
    Product product = productMapper.selectById(dto.getProductId());
    productMapper.decreaseStock(dto.getProductId(), dto.getQuantity());

    Order order = new Order();
    order.setUserId(dto.getUserId());
    order.setProductId(dto.getProductId());
    order.setProductName(product.getName());           // 先设值
    order.setAmount(calculateAmount(dto));              // 先设值
    orderMapper.insert(order);

    // 现在order的所有字段都有值了，再发消息
    OrderEvent event = buildEvent(order, product);
    kafkaTemplate.send("order-created", JSON.toJSONString(event));

    return OrderResult.success(order.getId());
}

```

**leader 给你补了一刀：**

> "还有一个问题。你在 `@Transactional` 方法里发 Kafka 消息。如果发完消息之后，事务提交失败回滚了怎么办？消息已经发出去了，消费者处理了一个根本不存在的订单。"

```java
// 最终方案：用事务性消息发送，或者事务提交后再发消息
// 小公司最简单的做法：TransactionSynchronization

@Transactional
public OrderResult createOrder(OrderCreateDTO dto) {
    // ... 扣库存、建订单 ...

    // 注册一个回调：事务提交成功后才发消息
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                kafkaTemplate.send("order-created",
                    JSON.toJSONString(buildEvent(order)));
            }
        }
    );

    return OrderResult.success(order.getId());
}
// 事务回滚了 → afterCommit不会执行 → 消息不会发出去
// 事务提交了 → afterCommit执行 → 消息发出去，且数据一定在DB里

```

---

### 5个问题的完整脉络

- **问题1: 技术选型** — 这个量级有必要用Kafka吗？ → **技术判断力**
- **问题2: 环境问题** — Docker里localhost连不上Kafka → **部署排查能力**
- **问题3: 外部依赖** — 微信API失败的分级处理 → **容错设计能力**
- **问题4: 突发流量** — 秒杀导致消费积压 → **瓶颈定位，不一定是Kafka的锅**
- **问题5: 数据一致** — 消息内容和DB数据对不上 → **事务与消息的协调**

> 这5个问题的特点：没有一个需要你懂Kafka的高深原理。都是业务开发中自然遇到的，但每一个都能让面试官确认你真正动手做过，而不是背的。
