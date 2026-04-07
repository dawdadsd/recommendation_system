package xiaowu.example.supplieretl.infrastructure.mock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.supplieretl.application.port.SupplierPullClient;

@Component
public class MockSupplierPullClient implements SupplierPullClient {

  private final ObjectMapper objectMapper;

  public MockSupplierPullClient(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

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
    String rawPayload = writePayload(Map.of(
        "supplierId", command.supplierId(),
        "supplierCode", command.supplierCode(),
        "records", List.of(
            Map.of("id", command.supplierId() * 1000 + 1, "name", "mock-record-1", "status", "ACTIVE"),
            Map.of("id", command.supplierId() * 1000 + 2, "name", "mock-record-2", "status", "ACTIVE"),
            Map.of("id", command.supplierId() * 1000 + 3, "name", "mock-record-3", "status", "INACTIVE")),
        "pulledAt", pulledAt.toString(),
        "nextCursor", nextCursor));
    return new PullResult(nextCursor, 3, pulledAt, rawPayload);
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while simulating supplier pull", ex);
    }
  }

  private String writePayload(Object payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to serialize mock supplier payload", ex);
    }
  }
}
