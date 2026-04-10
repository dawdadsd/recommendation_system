package xiaowu.example.supplieretl.datasource.security;

import java.util.List;

import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;

public record ConnectionSecurityAuditContext(
    String actorId,
    String clientIp,
    Long connectionId,
    DataSourceType dataSourceType,
    List<String> targetSummaries,
    List<String> resolvedAddresses) {

  public ConnectionSecurityAuditContext {
    targetSummaries = targetSummaries == null ? List.of() : List.copyOf(targetSummaries);
    resolvedAddresses = resolvedAddresses == null ? List.of() : List.copyOf(resolvedAddresses);
  }

  public String targetSummaryText() {
    return targetSummaries.isEmpty() ? null : String.join(", ", targetSummaries);
  }

  public String resolvedAddressesText() {
    return resolvedAddresses.isEmpty() ? null : String.join(", ", resolvedAddresses);
  }
}
