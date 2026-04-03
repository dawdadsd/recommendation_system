package xiaowu.example.supplieretl.infrastructure.kafka;

import java.util.Objects;

import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import xiaowu.example.supplieretl.application.port.RawDataPublisher;

/**
 * {@link RawDataPublisher} 的 Kafka 实现。
 *
 * <h2>主题说明</h2>
 * <ul>
 * <li>{@code supplier.raw.data} — 正常原始数据，下游 Spark Structured Streaming 消费</li>
 * <li>{@code supplier.raw.dlq} — 死信主题，人工排查 / 重放使用</li>
 * </ul>
 *
 * <h2>分区键</h2>
 * <p>
 * 分区键使用 {@code supplierId}（字符串形式），保证同一供应商的消息落在同一分区，
 * 让 Spark 侧做 per-supplier 有状态聚合时无需跨分区 shuffle。
 *
 * <h2>发布失败处理</h2>
 * <p>
 * 若 Kafka 写入失败（网络抖动、Broker 不可达），方法会抛出 RuntimeException，
 * 调用方（ExecutionApplicationService）可决定是否重试或记录本地日志。
 * 建议在生产上为 Kafka Producer 启用幂等模式（{@code enable.idempotence=true}），
 * 结合 {@code acks=all} 保证 at-least-once 语义。
 */
@Slf4j
public class KafkaRawDataPublisher implements RawDataPublisher {

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final SupplierKafkaProperties properties;

  public KafkaRawDataPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      SupplierKafkaProperties properties) {
    this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.properties = Objects.requireNonNull(properties);
  }

  @Override
  public void publish(RawDataEvent event) {
    Objects.requireNonNull(event, "event");
    String key = String.valueOf(event.supplierId());
    String payload = serialize(event);
    String topic = properties.topic().rawData();

    try {
      kafkaTemplate.send(topic, key, payload).join();
      log.debug("[RawDataPublisher] sent to {} supplierId={} recordCount={}",
          topic, event.supplierId(), event.recordCount());
    } catch (Exception ex) {
      throw new RuntimeException(
          "Failed to publish raw data to " + topic + " supplierId=" + event.supplierId(), ex);
    }
  }

  @Override
  public void publishDlq(DlqEvent event) {
    Objects.requireNonNull(event, "event");
    String key = String.valueOf(event.supplierId());
    String payload = serialize(event);
    String topic = properties.topic().rawDlq();

    try {
      kafkaTemplate.send(topic, key, payload).join();
      log.warn("[RawDataPublisher] DLQ → {} supplierId={} errorType={}",
          topic, event.supplierId(), event.errorType());
    } catch (Exception ex) {
      // DLQ 写入失败不能无声丢弃，至少打印 ERROR 保证可审计
      log.error("[RawDataPublisher] CRITICAL: DLQ write failed for supplierId={} errorType={}",
          event.supplierId(), event.errorType(), ex);
    }
  }

  private String serialize(Object obj) {
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (Exception ex) {
      throw new RuntimeException("Failed to serialize event: " + obj.getClass().getSimpleName(), ex);
    }
  }
}
