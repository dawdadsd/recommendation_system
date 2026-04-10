package xiaowu.example.supplieretl.datasource.application.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceSecurityAuditLog;
import xiaowu.example.supplieretl.datasource.domain.repository.DataSourceSecurityAuditLogRepository;
import xiaowu.example.supplieretl.datasource.security.AuditActionType;
import xiaowu.example.supplieretl.datasource.security.ConnectionSecurityAuditContext;
import xiaowu.example.supplieretl.datasource.security.SecurityAuditStatus;

@Service
public class DataSourceSecurityAuditApplicationService {

  private final DataSourceSecurityAuditLogRepository repository;

  public DataSourceSecurityAuditApplicationService(DataSourceSecurityAuditLogRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
  }

  public DataSourceSecurityAuditLog record(
      AuditActionType action,
      ConnectionSecurityAuditContext context,
      boolean success,
      SecurityAuditStatus status,
      String message,
      Map<String, Object> detail) {
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(status, "status must not be null");

    return repository.save(DataSourceSecurityAuditLog.create(
        action,
        context.connectionId(),
        context.dataSourceType(),
        context.actorId(),
        context.clientIp(),
        context.targetSummaryText(),
        context.resolvedAddressesText(),
        success,
        status,
        message,
        detail));
  }

  public List<DataSourceSecurityAuditLog> recent(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return repository.findRecent(limit);
  }
}
