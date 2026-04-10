package xiaowu.example.supplieretl.datasource.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import jakarta.servlet.http.HttpServletRequest;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.MysqlDataSourceConfig;
import xiaowu.example.supplieretl.datasource.infrastructure.config.ConnectionTestSecurityProperties;

class ConnectionTestGuardServiceTest {

  private ConnectionTestGuardService guardService;
  private HttpServletRequest request;

  @BeforeEach
  void setUp() {
    ConnectionTestSecurityProperties properties = new ConnectionTestSecurityProperties();
    properties.getRateLimit().setWindowSeconds(300);
    properties.getRateLimit().setMaxRequestsPerClientIp(10);
    properties.getRateLimit().setMaxRequestsPerResolvedTarget(10);
    properties.getRateLimit().setMaxRequestsPerClientIpTarget(2);

    guardService = new ConnectionTestGuardService(
        properties,
        new ConnectionTargetDescriptorResolver());
    request = Mockito.mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn("203.0.113.10");
  }

  @Test
  void guardTransientShouldRejectLocalhostTarget() {
    ConnectionSecurityAuditContext context = guardService.inspectTransient(
        request,
        DataSourceType.MYSQL,
        new MysqlDataSourceConfig(
            "jdbc:mysql://localhost:3306/test",
            "root",
            null,
            null,
            null));
    assertThatThrownBy(() -> guardService.enforce(context))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Localhost");
  }

  @Test
  void guardTransientShouldRateLimitRepeatedTargetFromSameIp() {
    MysqlDataSourceConfig config = new MysqlDataSourceConfig(
        "jdbc:mysql://8.8.8.8:3306/test",
        "root",
        null,
        null,
        null);

    ConnectionSecurityAuditContext context = guardService.inspectTransient(request, DataSourceType.MYSQL, config);

    guardService.enforce(context);
    guardService.enforce(context);

    assertThatThrownBy(() -> guardService.enforce(context))
        .isInstanceOf(ConnectionTestRateLimitException.class)
        .hasMessageContaining("same client");
  }
}
