package xiaowu.example.Kafka_Idempotent_Demo.infrastructure.idempotency;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.example.Kafka_Idempotent_Demo.application.port.IdempotencyGuard;
import xiaowu.example.Kafka_Idempotent_Demo.domain.exception.TransientEtlException;
import xiaowu.example.Kafka_Idempotent_Demo.domain.model.EventId;

/**
 * Redis 实现的幂等屏障（推荐方案的第一道防线）。
 *
 * <p>
 * 核心命令：{@code SET key value NX EX ttl}（即 {@code setIfAbsent}）——
 * 这是 Redis 单条原子指令，不会出现"先 GET 后 SET"的竞态条件。
 *
 * <p>
 * 性能特征：
 * <ul>
 * <li>单次调用 ≈ 1ms（同机房 Redis）</li>
 * <li>不依赖业务数据库，应用层无负担</li>
 * <li>TTL 自动过期，避免无限增长</li>
 * </ul>
 *
 * <p>
 * 风险与缓解：
 * <ul>
 * <li>Redis 故障 → 幂等失效。务必<b>同时启用</b> DB 唯一索引兜底
 * （参见 {@link DualLayerIdempotencyGuard}）</li>
 * <li>TTL 设置过短 → 旧消息回放时已过期。建议 TTL ≥ Kafka 保留期</li>
 * </ul>
 */
@Slf4j
@Component("redisIdempotencyGuard")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "demo.kafka-idempotent", name = "enabled", havingValue = "true")
public class RedisIdempotencyGuard implements IdempotencyGuard {

  private static final String KEY_PREFIX = "kafka:consumed:";

  private final StringRedisTemplate redis;

  /**
   * 幂等键的过期时间，从配置读取。
   * 默认 7 天，应当 ≥ Kafka 的消息保留期（{@code log.retention.hours}）。
   */
  @Value("${demo.kafka-idempotent.ttl-days:7}")
  private int ttlDays;

  /** TTL 抖动上限（小时）。0 表示关闭抖动，默认 ±10% */
  @Value("${demo.kafka-idempotent.ttl-jitter-hours:16}")
  private int jitterHours;

  /**
   * 按 topic 覆盖 TTL：demo.kafka-idempotent.ttl-overrides.order-topic=14
   *
   * #{} 表示：先把里面的内容当成 SpEL 表达式来解析，再注入到字段里
   */
  @Value("#{${demo.kafka-idempotent.ttl-overrides:{:}}}")
  private Map<String, Integer> ttlOverrides;

  @SuppressWarnings("key and ttl value not null")
  @Override
  public boolean tryAcquire(String topic, EventId eventId) {
    String key = buildKey(topic, eventId);
    Duration ttl = computeTtl(topic);
    try {
      Boolean firstTime = redis.opsForValue()
          .setIfAbsent(key, "1", ttl);
      // setIfAbsent 在某些 Redis 配置下可能返回 null（如 cluster + 部分超时）
      // 保守处理：null 当作"无法判断" → 抛 Transient 让上游重试
      if (firstTime == null) {
        throw new TransientEtlException(
            "Redis SETNX 返回 null，无法判定幂等状态: key=" + key, null);
      }
      return firstTime;
    } catch (TransientEtlException e) {
      throw e;
    } catch (Exception e) {
      // Redis 网络异常等：归类为瞬时失败，由消费者层决定重试
      throw new TransientEtlException("Redis 访问失败: key=" + key, e);
    }
  }

  /**
   * 计算最终 TTL：基础值（按 topic 覆盖） + 随机抖动。
   *
   * <p>
   * 抖动只**正向**叠加，确保 TTL ≥ 配置下界，不会让过期早于 Kafka 保留期。
   */
  private Duration computeTtl(String topic) {
    int baseDays = ttlOverrides.getOrDefault(topic, ttlDays);
    Duration base = Duration.ofDays(baseDays);
    if (jitterHours <= 0) {
      return base;
    }
    long jitterSec = ThreadLocalRandom.current().nextLong(Duration.ofHours(jitterHours).toSeconds());
    return base.plusSeconds(jitterSec);
  }

  @Override
  public void release(String topic, EventId eventId) {
    String key = buildKey(topic, eventId);
    try {
      Boolean removed = redis.delete(key);
      log.debug("[Idempotency] release key={} removed={}", key, removed);
    } catch (Exception e) {
      // release 失败不影响业务正确性（最坏情况是后续重试被幂等拦截）
      // 仅记录日志，不抛异常
      log.warn("[Idempotency] release 失败 key={}, 忽略此异常", key, e);
    }
  }

  private String buildKey(String topic, EventId eventId) {
    return KEY_PREFIX + topic + ":" + eventId.value();
  }
}
