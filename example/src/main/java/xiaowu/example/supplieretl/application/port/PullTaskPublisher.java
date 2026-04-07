package xiaowu.example.supplieretl.application.port;

import java.time.LocalDateTime;

/**
 * 拉取任务发布端口。
 */
public interface PullTaskPublisher {

  void publishPullRequested(PullRequestedEvent event);

  /**
   * 供应商拉取请求事件
   */
  record PullRequestedEvent(
      Long supplierId,
      String supplierCode,
      String lastCursor,
      LocalDateTime scheduledAt,
      LocalDateTime leaseUntil) {
  }
}
