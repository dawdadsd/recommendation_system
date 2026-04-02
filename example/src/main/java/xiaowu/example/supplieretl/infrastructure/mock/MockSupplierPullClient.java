package xiaowu.example.supplieretl.infrastructure.mock;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import xiaowu.example.supplieretl.application.port.SupplierPullClient;

@Component
public class MockSupplierPullClient implements SupplierPullClient {

  @Override
  public PullResult pull(PullCommand command) {
    if (command.supplierCode().contains("FAIL_ONCE") && command.retryCount() == 0) {
      throw new IllegalStateException("Simulated supplier timeout on first attempt");
    }

    if (command.supplierCode().contains("SLOW")) {
      sleep(200);
    }

    LocalDateTime pulledAt = LocalDateTime.now();
    String nextCursor = "cursor-" + command.supplierId() + "-" + pulledAt.toLocalTime().withNano(0);
    return new PullResult(nextCursor, 10, pulledAt);
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while simulating supplier pull", ex);
    }
  }
}
