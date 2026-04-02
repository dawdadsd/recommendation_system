package xiaowu.example.supplieretl.application.port;

import java.time.LocalDateTime;

public interface PullTaskPublisher {

  void publishPullRequested(PullRequestedEvent event);

  record PullRequestedEvent(
      Long supplierId,
      String supplierCode,
      String lastCursor,
      LocalDateTime scheduledAt,
      LocalDateTime leaseUntil) {
  }
}
