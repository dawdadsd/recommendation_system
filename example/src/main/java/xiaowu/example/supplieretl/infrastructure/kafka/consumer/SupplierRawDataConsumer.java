package xiaowu.example.supplieretl.infrastructure.kafka.consumer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.example.supplieretl.application.port.RawDataPublisher.RawDataEvent;
import xiaowu.example.supplieretl.application.service.SupplierRawDataProcessingApplicationService;

/**
 * Kafka 监听器，负责消费 supplier.raw.data 并触发标准化落库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "supplier.raw-data-consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SupplierRawDataConsumer {

  private final SupplierRawDataProcessingApplicationService rawDataProcessingApplicationService;
  private final ObjectMapper objectMapper;

  @KafkaListener(topics = "${supplier.kafka.topic.raw-data}", groupId = "${supplier.kafka.consumer.raw-data-group-id:example-supplier-raw-data-worker}")
  public void onMessage(String payload) {
    try {
      RawDataEvent event = objectMapper.readValue(payload, RawDataEvent.class);
      var result = rawDataProcessingApplicationService.process(event);

      if (result.success()) {
        log.info(
            "[RawDataConsumer] supplierId={} parsed={} upserted={} staleSkipped={}",
            result.supplierId(),
            result.parsedCount(),
            result.upsertedCount(),
            result.staleSkippedCount());
      } else {
        log.warn(
            "[RawDataConsumer] supplierId={} transform failed, error={}",
            result.supplierId(),
            result.errorMessage());
      }
    } catch (Exception ex) {
      log.error("[RawDataConsumer] failed to process payload={}", payload, ex);
      throw new IllegalStateException("Failed to process supplier raw data event", ex);
    }
  }
}
