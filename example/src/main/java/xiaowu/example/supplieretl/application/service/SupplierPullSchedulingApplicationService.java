package xiaowu.example.supplieretl.application.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import xiaowu.example.supplieretl.application.port.PullTaskPublisher;
import xiaowu.example.supplieretl.application.port.PullTaskPublisher.PullRequestedEvent;
import xiaowu.example.supplieretl.domain.entity.SupplierConnection;
import xiaowu.example.supplieretl.domain.repository.SupplierConnectionRepository;

/**
 * 调度供应商拉取任务的应用服务。
 *
 * <p>
 * 调度器本身不直接调用供应商API。
 * 它的工作是寻找适当的联系、获得租约并发布
 * 将请求拉入 Kafka，以便工作人员稍后可以执行。
 */
public class SupplierPullSchedulingApplicationService {

  private final SupplierConnectionRepository supplierConnectionRepository;
  private final PullTaskPublisher pullTaskPublisher;

  public SupplierPullSchedulingApplicationService(
      SupplierConnectionRepository supplierConnectionRepository,
      PullTaskPublisher pullTaskPublisher) {
    this.supplierConnectionRepository = Objects.requireNonNull(
        supplierConnectionRepository,
        "supplierConnectionRepository");
    this.pullTaskPublisher = Objects.requireNonNull(pullTaskPublisher, "pullTaskPublisher");
  }

  /**
   * 调度到期的供应商连接，尝试获取租约并发布拉取请求。
   * 
   * @doc : 默认发布到kafkfa
   * @param command 调度命令，包含批次大小和租约秒数
   * @return 调度结果，包含扫描的连接数、成功获取租约数、成功发布任务数和发布失败的供应商ID列表
   */
  public SchedulingResult scheduleDueConnections(ScheduleCommand command) {
    Objects.requireNonNull(command, "command");
    validateCommand(command);

    LocalDateTime now = LocalDateTime.now();
    List<SupplierConnection> candidates = supplierConnectionRepository.findSchedulableConnections(
        now,
        command.batchSize());

    int leaseAcquired = 0;
    int publishedTasks = 0;
    List<Long> publishFailedSupplierIds = new ArrayList<>();

    for (SupplierConnection connection : candidates) {
      LocalDateTime leaseUntil = now.plusSeconds(command.leaseSeconds());
      boolean acquired = supplierConnectionRepository.tryAcquireLease(
          connection.getSupplierId(),
          connection.getVersion(),
          now,
          leaseUntil,
          now);

      if (!acquired) {
        continue;
      }

      leaseAcquired++;
      connection.acquireLease(leaseUntil);

      try {
        // 默认情况下会发布到kafka，因为在configuration中绑定了SupplierKafkaConfiguration
        pullTaskPublisher.publishPullRequested(new PullRequestedEvent(
            connection.getSupplierId(),
            connection.getSupplierCode(),
            connection.getLastCursor(),
            now,
            leaseUntil));
        publishedTasks++;
      } catch (RuntimeException ex) {
        boolean released = supplierConnectionRepository.releaseLease(
            connection.getSupplierId(),
            connection.getVersion(),
            LocalDateTime.now());
        if (!released) {
          throw new IllegalStateException(
              "Failed to release lease after Kafka publish failure for supplierId="
                  + connection.getSupplierId(),
              ex);
        }
        publishFailedSupplierIds.add(connection.getSupplierId());
      }
    }

    return new SchedulingResult(
        candidates.size(),
        leaseAcquired,
        publishedTasks,
        List.copyOf(publishFailedSupplierIds));
  }

  /**
   * 验证调度命令的合法性，确保批次大小和租约秒数为正整数。
   *
   * @param command 调度命令，包含批次大小和租约秒数
   */
  private static void validateCommand(ScheduleCommand command) {
    if (command.batchSize() <= 0) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    if (command.leaseSeconds() <= 0) {
      throw new IllegalArgumentException("leaseSeconds must be positive");
    }
  }

  public record ScheduleCommand(
      int batchSize,
      long leaseSeconds) {
  }

  public record SchedulingResult(
      int scannedConnections,
      int leaseAcquired,
      int publishedTasks,
      List<Long> publishFailedSupplierIds) {
  }
}
