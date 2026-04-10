package xiaowu.example.supplieretl.datasource.domain.entity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

import xiaowu.example.supplieretl.datasource.security.AuditActionType;
import xiaowu.example.supplieretl.datasource.security.SecurityAuditStatus;

public final class DataSourceSecurityAuditLog {

  private final Long id;
  private final AuditActionType action;
  private final Long connectionId;
  private final DataSourceType dataSourceType;
  private final String actorId;
  private final String clientIp;
  private final String targetSummary;
  private final String resolvedAddresses;
  private final boolean success;
  private final SecurityAuditStatus status;
  private final String message;
  private final Map<String, Object> detail;
  private final LocalDateTime createdAt;

  private DataSourceSecurityAuditLog(
      Long id,
      AuditActionType action,
      Long connectionId,
      DataSourceType dataSourceType,
      String actorId,
      String clientIp,
      String targetSummary,
      String resolvedAddresses,
      boolean success,
      SecurityAuditStatus status,
      String message,
      Map<String, Object> detail,
      LocalDateTime createdAt) {
    if (id != null && id <= 0) {
      throw new IllegalArgumentException("id must be positive when present");
    }
    if (connectionId != null && connectionId <= 0) {
      throw new IllegalArgumentException("connectionId must be positive when present");
    }
    this.id = id;
    this.action = Objects.requireNonNull(action, "action must not be null");
    this.connectionId = connectionId;
    this.dataSourceType = Objects.requireNonNull(dataSourceType, "dataSourceType must not be null");
    this.actorId = normalizeNullableText(actorId);
    this.clientIp = requireText(clientIp, "clientIp must not be blank");
    this.targetSummary = normalizeNullableText(targetSummary);
    this.resolvedAddresses = normalizeNullableText(resolvedAddresses);
    this.success = success;
    this.status = Objects.requireNonNull(status, "status must not be null");
    this.message = requireText(message, "message must not be blank");
    this.detail = detail == null ? Map.of() : Map.copyOf(detail);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  public static DataSourceSecurityAuditLog create(
      AuditActionType action,
      Long connectionId,
      DataSourceType dataSourceType,
      String actorId,
      String clientIp,
      String targetSummary,
      String resolvedAddresses,
      boolean success,
      SecurityAuditStatus status,
      String message,
      Map<String, Object> detail) {
    return new DataSourceSecurityAuditLog(
        null,
        action,
        connectionId,
        dataSourceType,
        actorId,
        clientIp,
        targetSummary,
        resolvedAddresses,
        success,
        status,
        message,
        detail,
        LocalDateTime.now());
  }

  public static DataSourceSecurityAuditLog restore(
      Long id,
      AuditActionType action,
      Long connectionId,
      DataSourceType dataSourceType,
      String actorId,
      String clientIp,
      String targetSummary,
      String resolvedAddresses,
      boolean success,
      SecurityAuditStatus status,
      String message,
      Map<String, Object> detail,
      LocalDateTime createdAt) {
    return new DataSourceSecurityAuditLog(
        id,
        action,
        connectionId,
        dataSourceType,
        actorId,
        clientIp,
        targetSummary,
        resolvedAddresses,
        success,
        status,
        message,
        detail,
        createdAt);
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

  public AuditActionType getAction() {
    return action;
  }

  public Long getConnectionId() {
    return connectionId;
  }

  public DataSourceType getDataSourceType() {
    return dataSourceType;
  }

  public String getActorId() {
    return actorId;
  }

  public String getClientIp() {
    return clientIp;
  }

  public String getTargetSummary() {
    return targetSummary;
  }

  public String getResolvedAddresses() {
    return resolvedAddresses;
  }

  public boolean isSuccess() {
    return success;
  }

  public SecurityAuditStatus getStatus() {
    return status;
  }

  public String getMessage() {
    return message;
  }

  public Map<String, Object> getDetail() {
    return detail;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
