package xiaowu.example.supplieretl.datasource.application.model;

import java.time.LocalDateTime;
import java.util.Map;

public record ConnectionTestResult(
    boolean success,
    String message,
    Map<String, Object> detail,
    LocalDateTime testedAt) {

  public ConnectionTestResult {
    detail = detail == null ? Map.of() : Map.copyOf(detail);
    testedAt = testedAt == null ? LocalDateTime.now() : testedAt;
  }

  public static ConnectionTestResult success(String message, Map<String, Object> detail) {
    return new ConnectionTestResult(true, message, detail, LocalDateTime.now());
  }

  public static ConnectionTestResult failure(String message, Map<String, Object> detail) {
    return new ConnectionTestResult(false, message, detail, LocalDateTime.now());
  }
}
