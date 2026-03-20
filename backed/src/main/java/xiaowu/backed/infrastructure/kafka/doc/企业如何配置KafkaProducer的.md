## 企业级 Producer 配置：逐层拆解

先看你现在的问题，再给出完整方案。

### 你现在的配置有什么不足

```java
configs.put(ProducerConfig.ACKS_CONFIG, "1");          // leader写了就算成功，leader挂了数据丢失
// 没有配置 retries                                    → 网络抖动直接失败
// 没有配置 idempotence                                → 重试可能产生重复消息
// 没有配置 compression                                → 浪费带宽和磁盘
// 硬编码所有参数                                      → 环境切换困难
// 只有一个 KafkaTemplate                              → 所有业务共用同一套配置

```

### 企业级完整方案

```java
package xiaowu.backed.infrastructure.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    // ① 不再用 @Value 手动读一个个属性
    //    而是注入 Spring Boot 自动绑定的 KafkaProperties
    //    它会读取 application.yml 里 spring.kafka.* 的所有配置
    private final KafkaProperties kafkaProperties;

    public KafkaProducerConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    // ════════════════════════════════════════
    //  核心：通用 Producer 工厂
    // ════════════════════════════════════════
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        // 从 KafkaProperties 拿到基础配置（bootstrap-servers等）
        Map<String, Object> props = kafkaProperties.buildProducerProperties(null);

        // ── 可靠性配置 ──────────────────────

        // acks=all：消息必须写入所有ISR副本才算成功
        // 配合 broker 端 min.insync.replicas=2，保证至少2个副本持久化
        // 代价：延迟略高（多等几毫秒），但数据零丢失
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        // 开启幂等生产者：Kafka 会对每条消息分配序列号
        // 即使网络超时触发重试，broker 也能识别重复消息并去重
        // 这是"精确一次"语义的基础
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // 重试次数：网络抖动、leader选举时自动重试
        // 幂等模式下重试不会产生重复，放心设大一点
        props.put(ProducerConfig.RETRIES_CONFIG, 3);

        // 重试间隔：避免瞬间洪峰式重试打垮broker
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100);

        // 发送超时：单次send()的最大等待时间
        // 超过这个时间还没收到ack → 触发重试或抛异常
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);

        // 总交付超时：从send()调用到最终成功/失败的最大时间
        // 包含所有重试时间，超过就放弃
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);

        // 幂等模式下，未确认请求数上限必须 ≤ 5
        // 设为5是Kafka允许的最大值，兼顾吞吐和顺序性
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // ── 吞吐量配置 ──────────────────────

        // 批量大小：累积到32KB才发送一批
        // 你原来16KB偏小，32KB是多数场景的甜点
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);

        // 等待时间：即使没凑够一批，等10ms也发送
        // 用10ms延迟换取更好的批量效果
        // 你原来5ms偏激进，10ms是更好的平衡点
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);

        // 发送缓冲区：64MB（你原来32MB）
        // 如果生产速度 > 发送速度，消息暂存在这个buffer里
        // buffer满了 → send()会阻塞，直到max.block.ms超时
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 67108864L);

        // buffer满时的最大阻塞时间
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 60000);

        // ── 压缩配置 ──────────────────────

        // LZ4压缩：CPU开销小，压缩率60-70%
        // JSON消息压缩效果极好，省带宽省磁盘
        // 可选：snappy(更快但压缩率略低), zstd(压缩率最高但CPU高), gzip(兼容性好但最慢)
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,
                  CompressionType.LZ4.name);

        // ── 序列化 ──────────────────────
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                  StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                  StringSerializer.class);

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory());
        // 设置默认Topic（可选），发送时不指定Topic就用这个
        // template.setDefaultTopic("user-events");

        // 开启观测（Spring Boot 3.x + Micrometer）
        // 自动采集发送延迟、成功率、吞吐量等指标
        template.setObservationEnabled(true);
        return template;
    }
}

```

**对应的 `application.yml` 配置**

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka-1:9092,kafka-2:9092,kafka-3:9092}
    producer:
      # 这些也可以在yml里配，会被KafkaProperties读取
      # 但复杂配置建议在Java Config里写，有编译检查和注释
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
        compression.type: lz4
```

### 参数之间的关系图

这是理解的关键——参数不是孤立的，是一个体系：

```text
                      数据可靠性
                          │
              ┌───────────┼───────────┐
              │           │           │
          acks=all   idempotence  retries=3
              │           │           │
              │     去重保证序列号    重试间隔100ms
              │           │           │
              └───────────┼───────────┘
                          │
                max.in.flight ≤ 5
                (幂等模式硬性要求)


                      吞吐量
                          │
              ┌───────────┼───────────┐
              │           │           │
        batch.size=32KB  linger=10ms  compression=lz4
              │           │           │
        攒够32KB发一批  等10ms再发   压缩后批量更大
              │           │           │
              └───────────┼───────────┘
                          │
                 buffer.memory=64MB
                 (生产>消费时的缓冲)

```

### 不同业务场景的参数调整

- **场景A：用户行为事件（你的user-events）**
- → 量大、允许极小延迟、不能丢
- → acks=all, batch=32KB, linger=10ms, compression=lz4
- → 上面的配置就是为这个场景设计的

- **场景B：支付/订单消息**
- → 量小、绝对不能丢、不能重复
- → acks=all, idempotence=true, retries=10
- → batch=1KB, linger=0 (立即发送，不等批量)
- → 牺牲吞吐换绝对可靠

- **场景C：日志采集**
- → 量极大、丢几条无所谓、要快
- → acks=0 或 1, batch=64KB, linger=50ms
- → compression=zstd (最大压缩率)
- → 牺牲可靠性换最大吞吐

### 你的代码 vs 企业级的核心区别总结

| 维度         | 你现在的      | 企业级                     | 为什么                                                     |
| ------------ | ------------- | -------------------------- | ---------------------------------------------------------- |
| **配置来源** | `@Value` 硬读 | `KafkaProperties` 自动绑定 | 利用 Spring Boot 自动配置，减少样板代码，支持 profile 切换 |
| **acks**     | `"1"`         | `"all"`                    | leader挂了不丢数据                                         |
| **幂等**     | 未开启        | `true`                     | 重试不产生重复消息                                         |
| **重试**     | 未配置        | 3次 + 100ms间隔            | 网络抖动自动恢复                                           |
| **压缩**     | 无            | LZ4                        | JSON消息压缩60%+，省带宽省磁盘                             |
| **批量**     | 16KB / 5ms    | 32KB / 10ms                | 更好的吞吐/延迟平衡                                        |
| **缓冲区**   | 32MB          | 64MB                       | 应对突发流量                                               |
| **可观测性** | 无            | `setObservationEnabled`    | 接入监控系统，生产必备                                     |

> 一句话：你现在的配置是"能跑"，企业级是"不丢数据、能扛流量、出了问题能定位"。每一个参数的差异背后都是一个生产事故的教训。

---

排版已经全部按照 Markdown 规范转换完成。接下来，你是想把这份配置直接**抄到你的 Spring Boot 项目里跑一下测试**，还是想继续深挖 **Kafka 消费者端（Consumer）的企业级参数调优**？
