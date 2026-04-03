package xiaowu.example.supplieretl.infrastructure.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.example.supplieretl.application.service.SupplierPullSchedulingApplicationService;
import xiaowu.example.supplieretl.application.service.SupplierPullSchedulingApplicationService.ScheduleCommand;
import xiaowu.example.supplieretl.infrastructure.config.SupplierSchedulingProperties;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "supplier.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SupplierPullSchedulingJob {

  private final SupplierPullSchedulingApplicationService schedulingApplicationService;
  private final SupplierSchedulingProperties properties;

  /**
   * 扫描并派发任务
   */
  @Scheduled(fixedDelayString = "${supplier.scheduling.fixed-delay-ms:10000}")
  public void scanAndDispatch() {
    var result = schedulingApplicationService.scheduleDueConnections(new ScheduleCommand(
        properties.batchSize(),
        properties.leaseSeconds()));

    if (result.scannedConnections() == 0) {
      log.debug("[SupplierScheduler] no due supplier connections found");
      return;
    }

    log.info(
        "[SupplierScheduler] scanned={}, leased={}, published={}, publishFailures={}",
        result.scannedConnections(),
        result.leaseAcquired(),
        result.publishedTasks(),
        result.publishFailedSupplierIds());
  }
}
