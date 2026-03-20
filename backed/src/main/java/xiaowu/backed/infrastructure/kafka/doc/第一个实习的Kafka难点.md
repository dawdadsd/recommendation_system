## 真实场景：电商数据平台 — 用户行为日志实时入仓

### 背景设定

你是数据平台组的实习生，组里8个人。
你的leader是P7，负责公司的实时数据管道。

**公司：** 中型电商，日活200万，日均行为事件约8000万条。

**现有架构（你来之前就搭好了）：**

```text
APP/Web → 埋点网关(已有) → Kafka集群(已有) → ??? → ClickHouse(已有)
                                           ↑
                                      这段空着，你来填

```

**你的leader第一天给你说：**

> "现在Kafka里的埋点数据没有实时落仓，数据分析师只能用T+1的离线数据（Hive里昨天的），产品那边一直催着要实时看板。你写一个Kafka Consumer服务，把 `user-track-events` 这个Topic的数据实时写到ClickHouse里。不难，给你两周。"

**你拿到的东西：**

- **Topic:** `user-track-events`
- **分区数:** 12
- **消息格式(JSON):**

```json
{
  "event_id": "evt_a]3f8b2c",
  "user_id": 182934,
  "event_type": "PRODUCT_CLICK",
  "page": "search_result",
  "product_id": 50821,
  "properties": { "query": "运动鞋", "position": 3 },
  "device": "iOS_17.2",
  "app_version": "8.12.0",
  "timestamp": 1710300000000
}
```

- **日均数据量:** ~8000万条，峰值晚8点约3000条/秒
- **ClickHouse目标表:** `ods.user_track_events`（已建好）

---

### 你写的第一版代码（能跑）

```java
@Component
public class TrackEventSinkService {

      @Autowired
      private ClickHouseJdbcTemplate clickHouseJdbc;

      @KafkaListener(
          topics = "user-track-events",
          groupId = "ck-sink-group",
          concurrency = "4"
      )
      public void consume(ConsumerRecord<String, String> record,
                          Acknowledgment ack) {
          TrackEvent event = JSON.parseObject(record.value(), TrackEvent.class);
          clickHouseJdbc.insert("INSERT INTO ods.user_track_events VALUES (...)",
                                event);
          ack.acknowledge();
      }

}

```

你提了MR（Merge Request），leader review 后给你提了以下问题——

---

### 问题1：逐条写入 ClickHouse，性能完全不行

**leader 的原话：**

> "你这一条一条INSERT，ClickHouse最怕这个。它是列式存储，设计上就是批量写入的。你这样写，3000条/秒的峰值根本扛不住，ClickHouse的merge线程会被打爆。去查一下ClickHouse的写入最佳实践，改成批量写入。"

**问题本质：**

- ClickHouse每次INSERT会生成一个data part（数据分片）
- 每秒3000次INSERT = 每秒3000个part
- ClickHouse后台要不停merge这些part
- 超过负荷 → "Too many parts" 报错 → 整个表不可写
- **官方建议：** 每秒不超过1次INSERT，每次INSERT包含几千到几万行

**你需要改成：**

```java
@Component
public class TrackEventSinkService {

      // 内存缓冲区
      private final BlockingQueue<TrackEvent> buffer =
          new LinkedBlockingQueue<>(50000);

      // 批量大小和时间窗口
      private static final int BATCH_SIZE = 5000;
      private static final long FLUSH_INTERVAL_MS = 10_000; // 10秒

      @KafkaListener(topics = "user-track-events",
                     groupId = "ck-sink-group", concurrency = "4")
      public void consume(ConsumerRecord<String, String> record,
                          Acknowledgment ack) {
          TrackEvent event = JSON.parseObject(record.value(), TrackEvent.class);
          buffer.offer(event);
          ack.acknowledge();
      }

      // 定时刷写线程
      @Scheduled(fixedRate = 10_000)
      public void flushToClickHouse() {
          List<TrackEvent> batch = new ArrayList<>();
          buffer.drainTo(batch, BATCH_SIZE); // 一次最多取5000条

          if (!batch.isEmpty()) {
              // 一次INSERT 5000行，ClickHouse很happy
              clickHouseJdbc.batchInsert(
                  "INSERT INTO ods.user_track_events VALUES (?,?,?,?,?,?,?,?)",
                  batch);
              log.info("批量写入ClickHouse: {} 条", batch.size());
          }
      }

}

```

**你面试时怎么讲：**

> "最开始逐条INSERT，ClickHouse直接报Too many parts。查了官方文档后改成内存攒批 + 定时刷写，每10秒或攒够5000条写一次。改完之后ClickHouse的parts merge压力从每秒3000次降到每秒不到1次。"

---

### 问题2：先acknowledge再写 ClickHouse，数据会丢

**leader 的原话：**

> "你仔细看你的代码逻辑——消息放进buffer就acknowledge了，但buffer里的数据还没写到ClickHouse。如果这时候服务挂了或者发版重启，buffer里的数据就没了。发版重启可是每周都有的事。"

**问题本质：**
**时间线：**

- **t1:** 消费消息 offset=1000，放入buffer，ack → Kafka记录消费到1000了
- **t2:** 消费消息 offset=1001，放入buffer，ack → Kafka记录消费到1001了
- **t3:** buffer累积到3000条，还没到5000条阈值
- **t4:** 运维发版重启服务 **← buffer里3000条全丢**
- **t5:** 服务重启，从offset=1001之后继续消费 **← 那3000条永远不会再被消费**

这不是"应用崩溃"，而是每周都有的正常发版！

**你需要改成：**

```java
// 方案：攒批完成 + ClickHouse写入成功后，才提交offset

// 不用 @KafkaListener 的自动模式了
// 改用手动拉取 + 手动提交
@Component
public class TrackEventSinkService implements Runnable {

      @Autowired
      private ConsumerFactory<String, String> consumerFactory;

      private volatile boolean running = true;

      @Override
      public void run() {
          Consumer<String, String> consumer = consumerFactory.createConsumer(
              "ck-sink-group", "");
          consumer.subscribe(List.of("user-track-events"));

          while (running) {
              // 拉一批消息
              ConsumerRecords<String, String> records =
                  consumer.poll(Duration.ofMillis(1000));

              if (records.isEmpty()) continue;

              // 解析
              List<TrackEvent> batch = new ArrayList<>();
              for (ConsumerRecord<String, String> r : records) {
                  batch.add(JSON.parseObject(r.value(), TrackEvent.class));
              }

              // 先写ClickHouse
              clickHouseJdbc.batchInsert(batch);

              // 写成功后才提交offset
              consumer.commitSync();
              // 如果写ClickHouse时服务挂了 → offset没提交
              // 重启后从上次提交的offset重新消费 → 不丢数据
          }
      }

}

```

- **leader追问：** 那重启后重复消费的那批数据不就重复写入了吗？
- **你的回答：** ClickHouse那边用 `ReplacingMergeTree` 引擎，按 `event_id` 去重。或者在写入前用 `event_id` 做幂等校验。最终一致性没问题，只要不丢就行。

---

### 问题3：脏数据打爆服务

**leader 的原话：**

> "线上出问题了。有个业务方的埋点SDK版本太老，timestamp字段传的是字符串不是数字，你的Consumer直接NPE了，挂了之后所有分区都停止消费，lag在飙。你先把服务恢复，然后加防御。"

**问题本质：**
实际收到的消息：
`{"event_id":"xxx", "timestamp":"2024-03-13 10:00:00", ...}` ← **字符串！**
↑ 预期是long型毫秒时间戳

`JSON.parseObject` 尝试把字符串赋值给 long 字段 → 异常
异常没catch → 消费线程挂了 → 整个消费者停工

**你需要做的（leader盯着你的紧急修复）：**

```java
// 第一步：加防御性解析，服务恢复（10分钟内要搞定）
public void consume(ConsumerRecords<String, String> records) {
      List<TrackEvent> batch = new ArrayList<>();

      for (ConsumerRecord<String, String> r : records) {
          try {
              TrackEvent event = parseEvent(r.value());
              if (event != null && isValid(event)) {
                  batch.add(event);
              } else {
                  // 无法解析的消息 → 发到死信Topic，不要阻塞主流程
                  kafkaTemplate.send("user-track-events-dlq", r.value());
                  metrics.counter("sink.parse.failed").increment();
              }
          } catch (Exception e) {
              kafkaTemplate.send("user-track-events-dlq", r.value());
              metrics.counter("sink.parse.error").increment();
              log.warn("消息解析失败，已转入DLQ: {}",
                       r.value().substring(0, Math.min(200, r.value().length())), e);
          }
      }

      if (!batch.isEmpty()) {
          clickHouseJdbc.batchInsert(batch);
      }

}

private boolean isValid(TrackEvent event) {
      if (event.getEventId() == null || event.getEventId().isBlank()) return false;
      if (event.getUserId() <= 0) return false;
      if (event.getTimestamp() <= 0
          || event.getTimestamp() > System.currentTimeMillis() + 86400000) return false;
      return true;
}

```

**leader后续让你做的：**

1. 统计DLQ里的脏数据量和来源（哪个 `app_version` 产生的）
2. 输出一份数据质量报告给埋点SDK团队
3. 加Grafana监控面板：解析失败率如果超过1%自动告警

**你面试时怎么讲：**

> "有一次某个旧版SDK传的timestamp格式不对，Consumer直接NPE停工了。紧急加了防御性解析和死信队列，先恢复服务。然后统计了DLQ里的脏数据分布，发现是8.9.0以下版本的SDK都有这个问题，推动客户端团队发了一次强制升级。之后加了解析失败率的监控，阈值设在1%。"

---

### 问题4：消费能力跟不上，晚高峰持续积压

**leader 的原话：**

> "最近两周晚8点到10点，lag一直在涨，从0涨到200万，到凌晨才消化完。产品说实时看板延迟半小时，不能接受。你排查一下瓶颈在哪，出个方案。"

**你的排查过程：**

- **第1步：确认瓶颈在哪一环**
  `kafka-consumer-groups.sh --describe --group ck-sink-group`
  结果：

```text
TOPIC             PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
user-track-events 0          38291002        38491233        200231
user-track-events 1          37182934        37382111        199177
...

```

12个分区都在积压，说明不是某个分区的问题

- **第2步：看消费者实例数 vs 分区数**
- 消费者实例：4个（你代码里 `concurrency="4"`）
- 分区数：12个
- → 每个消费者消费3个分区，还有扩容空间

- **第3步：看单个消费者的处理耗时**
- 日志显示：`batchInsert` 5000条耗时约800ms
- 理论吞吐：5000 / 0.8 = 6250条/秒/消费者
- 4个消费者总吞吐：25000条/秒
- 晚高峰流量：约30000条/秒
- → 差了5000条/秒，所以一直在积压

**你的方案（写在文档里给leader review）：**

- **方案A：扩消费者实例数（最快）**
- → `concurrency`从4改到8，或者部署2个服务实例各4线程
- → 总吞吐翻倍到50000条/秒，远超峰值
- → 风险：ClickHouse写入压力翻倍，需要确认ClickHouse扛得住

- **方案B：优化写入效率（根本解决）**
- → batch从5000提到20000
- → 用ClickHouse的异步INSERT（`async_insert`）
- → 预计单消费者吞吐从6250提升到20000+

- **方案C：流量削峰**
- → 埋点网关那边做采样，非核心事件（如页面曝光）采样50%
- → 数据量直接减半
- → 风险：数据分析师可能不接受采样数据

**leader的决定：** 先做A快速止血，再做B长期优化，C不做（数据不能采样）

**你面试时怎么讲：**

> "晚高峰消费lag持续积压。排查发现4个消费者线程总吞吐是25000/秒，但峰值流量30000/秒。短期把concurrency从4扩到8解决。长期优化了批量写入的batch size和ClickHouse的异步写入模式，单消费者吞吐从6250提到了接近20000。"

---

### 问题5：ClickHouse集群故障，Consumer要怎么办

**leader 的原话：**

> "下周三凌晨ClickHouse集群要扩容，预计停服2小时。你的Consumer不能挂也不能丢数据。想想怎么处理，写个方案。"

**问题本质：**
ClickHouse停了 → Consumer写入失败 → 怎么办？

- **选项A：** Consumer继续消费，数据暂存本地 → 恢复后补写
- → 本地存哪？内存撑不住2小时的量

- **选项B：Consumer暂停消费，让消息积压在Kafka里**
- → Kafka就是天然的缓冲区，消息在磁盘上保留7天
- → ClickHouse恢复后Consumer自动从断点继续消费
- **→ 这就是正确答案**

**你写的方案：**

```java
// 写入失败时暂停消费，而不是无限重试
public void run() {
      while (running) {
          ConsumerRecords<String, String> records =
              consumer.poll(Duration.ofMillis(1000));

          if (records.isEmpty()) continue;

          List<TrackEvent> batch = parse(records);

          boolean success = false;
          for (int retry = 0; retry < 3; retry++) {
              try {
                  clickHouseJdbc.batchInsert(batch);
                  success = true;
                  break;
              } catch (Exception e) {
                  log.warn("ClickHouse写入失败，第{}次重试", retry + 1, e);
                  Thread.sleep(5000 * (retry + 1)); // 退避等待
              }
          }

          if (success) {
              consumer.commitSync(); // 写成功才提交
          } else {
              // 3次都失败了 → ClickHouse可能真挂了
              // 暂停消费，让消息积在Kafka里
              log.error("ClickHouse不可用，暂停消费，等待恢复");
              consumer.pause(consumer.assignment()); // 暂停所有分区

              // 发告警
              alertService.send("ClickHouse写入失败，Consumer已暂停，请检查");

              // 每30秒探测一次ClickHouse是否恢复
              while (!isClickHouseHealthy()) {
                  Thread.sleep(30_000);
              }

              // 恢复消费
              consumer.resume(consumer.assignment());
              log.info("ClickHouse已恢复，继续消费");
          }
      }

}

private boolean isClickHouseHealthy() {
      try {
          clickHouseJdbc.execute("SELECT 1");
          return true;
      } catch (Exception e) {
          return false;
      }
}

```

**扩容当天的实际情况：**

- **00:00** ClickHouse开始停服
- **00:01** Consumer探测到写入失败，自动暂停，发出告警
- **00:01-02:00** 消息积压在Kafka里，lag涨到约2000万
- **02:00** ClickHouse恢复
- **02:01** Consumer自动恢复消费
- **02:45** lag消化完毕，回到0
- 全程零数据丢失，零人工干预

**你面试时怎么讲：**

> "ClickHouse有一次计划内扩容停服2小时。我在Consumer里做了健康探测机制——写入连续失败3次后暂停消费，让消息积压在Kafka里。每30秒探测ClickHouse是否恢复，恢复后自动继续。扩容当天积压了约2000万条，恢复后45分钟消化完，全程不需要人工介入。核心思路是利用Kafka本身作为缓冲区，不需要额外的本地存储。"

---

### 这5个问题的递进关系

- **问题1:** 逐条写入性能差 → 你学会了 **批量写入模式**
- **问题2:** 先ack再写导致数据丢失 → 你学会了 **offset提交时机**
- **问题3:** 脏数据打爆服务 → 你学会了 **防御性编程 + 死信队列**
- **问题4:** 消费能力不足积压 → 你学会了 **性能排查 + 扩容方案**
- **问题5:** 下游故障如何容错 → 你学会了 **优雅降级 + Kafka作缓冲**

这就是一个实习生2-3个月内真实会经历的成长路径。每个问题都不是你提前知道的，而是上线后被现实打脸，然后leader带着你排查解决的。面试时讲这些，面试官会觉得你确实在生产环境被毒打过。

---

排版完成。这份文档里的“坑”和“填坑过程”非常贴合你之前提到的，你在三月份开始重点学习 Kafka 并准备找更好工作的规划。你打算把这个场景作为你简历里的项目经验，还是作为面试时的实战案例来准备？
