package xiaowu.example.supplieretl.datasource.domain.repository;

import java.util.List;

import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceSecurityAuditLog;

public interface DataSourceSecurityAuditLogRepository {

  DataSourceSecurityAuditLog save(DataSourceSecurityAuditLog log);

  List<DataSourceSecurityAuditLog> findRecent(int limit);
}
