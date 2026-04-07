package xiaowu.example.supplieretl.infrastructure.kafka.consumer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.example.supplieretl.application.port.PullTaskPublisher.PullRequestedEvent;
import xiaowu.example.supplieretl.application.service.SupplierPullExecutionApplicationService;
import xiaowu.example.supplieretl.application.service.SupplierPullExecutionApplicationService.ExecuteCommand;
import xiaowu.example.supplieretl.infrastructure.config.SupplierWorkerProperties;

/**
 * Kafka 监听器，负责消费供应商拉取任务的消息，并调用 SupplierPullExecutionApplicationService 执行拉取任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "supplier.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SupplierPullTaskConsumer {

  private final SupplierPullExecutionApplicationService executionApplicationService;
  private final SupplierWorkerProperties workerProperties;
  private final ObjectMapper objectMapper;

  @KafkaListener(topics = "${supplier.kafka.topic.pull-request}")
  public void onMessage(String payload) {
    try {
      // 反序列化为对象
      PullRequestedEvent event = objectMapper.readValue(payload, PullRequestedEvent.class);
      var result = executionApplicationService.execute(new ExecuteCommand(
          event.supplierId(),
          workerProperties.retryDelaySeconds(),
          workerProperties.retryBackoffBaseSeconds(),
          workerProperties.retryBackoffMaxSeconds(),
          workerProperties.retryBackoffMaxJitterMs()));

      if (result.success()) {
        log.info(
            "[SupplierWorker] supplierId={} executed successfully, recordCount={}, nextCursor={}",
            result.supplierId(),
            result.recordCount(),
            result.lastCursor());
      } else {
        log.warn(
            "[SupplierWorker] supplierId={} execution failed, error={}",
            result.supplierId(),
            result.errorMessage());
      }
    } catch (Exception ex) {
      log.error("[SupplierWorker] failed to process payload={}", payload, ex);
      throw new IllegalStateException("Failed to process supplier pull task", ex);
    }
  }
}
