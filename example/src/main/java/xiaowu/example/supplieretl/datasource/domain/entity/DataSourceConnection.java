package xiaowu.example.supplieretl.datasource.domain.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import xiaowu.example.supplieretl.datasource.domain.model.DataSourceConfig;

/**
 * 数据源连接实体类，包含连接的基本信息和配置
 */
public final class DataSourceConnection {

  private final Long id;
  private final String connectionName;
  private final String description;
  private final DataSourceType type;
  private final DataSourceConfig config;
  private final LocalDateTime createdAt;
  private final LocalDateTime updatedAt;

  private DataSourceConnection(
      Long id,
      String connectionName,
      String description,
      DataSourceType type,
      DataSourceConfig config,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    if (id != null && id <= 0) {
      throw new IllegalArgumentException("id must be positive when present");
    }
    this.id = id;
    this.connectionName = requireText(connectionName, "connectionName must not be blank");
    this.description = normalizeNullableText(description);
    this.type = Objects.requireNonNull(type, "type must not be null");
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }

  public static DataSourceConnection create(
      String connectionName,
      String description,
      DataSourceType type,
      DataSourceConfig config) {
    LocalDateTime now = LocalDateTime.now();
    return new DataSourceConnection(
        null,
        connectionName,
        description,
        type,
        config,
        now,
        now);
  }

  public static DataSourceConnection restore(
      Long id,
      String connectionName,
      String description,
      DataSourceType type,
      DataSourceConfig config,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    return new DataSourceConnection(
        id,
        connectionName,
        description,
        type,
        config,
        createdAt,
        updatedAt);
  }

  private static String requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value.trim();
  }

  private static String normalizeNullableText(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public Long getId() {
    return id;
  }

  public String getConnectionName() {
    return connectionName;
  }

  public String getDescription() {
    return description;
  }

  public DataSourceType getType() {
    return type;
  }

  public DataSourceConfig getConfig() {
    return config;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
