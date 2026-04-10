package xiaowu.example.supplieretl.datasource.domain.entity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

public final class DataSourceConnectionTestLog {

  private final Long id;
  private final Long connectionId;
  private final DataSourceType dataSourceType;
  private final boolean success;
  private final String message;
  private final Map<String, Object> detail;
  private final LocalDateTime testedAt;

  private DataSourceConnectionTestLog(
      Long id,
      Long connectionId,
      DataSourceType dataSourceType,
      boolean success,
      String message,
      Map<String, Object> detail,
      LocalDateTime testedAt) {
    if (id != null && id <= 0) {
      throw new IllegalArgumentException("id must be positive when present");
    }
    if (connectionId == null || connectionId <= 0) {
      throw new IllegalArgumentException("connectionId must be positive");
    }
    this.id = id;
    this.connectionId = connectionId;
    this.dataSourceType = Objects.requireNonNull(dataSourceType, "dataSourceType must not be null");
    this.success = success;
    this.message = requireText(message, "message must not be blank");
    this.detail = detail == null ? Map.of() : Map.copyOf(detail);
    this.testedAt = Objects.requireNonNull(testedAt, "testedAt must not be null");
  }

  public static DataSourceConnectionTestLog create(
      Long connectionId,
      DataSourceType dataSourceType,
      boolean success,
      String message,
      Map<String, Object> detail,
      LocalDateTime testedAt) {
    return new DataSourceConnectionTestLog(
        null,
        connectionId,
        dataSourceType,
        success,
        message,
        detail,
        testedAt);
  }

  public static DataSourceConnectionTestLog restore(
      Long id,
      Long connectionId,
      DataSourceType dataSourceType,
      boolean success,
      String message,
      Map<String, Object> detail,
      LocalDateTime testedAt) {
    return new DataSourceConnectionTestLog(
        id,
        connectionId,
        dataSourceType,
        success,
        message,
        detail,
        testedAt);
  }

  private static String requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value.trim();
  }

  public Long getId() {
    return id;
  }

  public Long getConnectionId() {
    return connectionId;
  }

  public DataSourceType getDataSourceType() {
    return dataSourceType;
  }

  public boolean isSuccess() {
    return success;
  }

  public String getMessage() {
    return message;
  }

  public Map<String, Object> getDetail() {
    return detail;
  }

  public LocalDateTime getTestedAt() {
    return testedAt;
  }
}
