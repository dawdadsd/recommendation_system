package xiaowu.example.supplieretl.interfaces.rest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import xiaowu.example.supplieretl.datasource.application.model.ConnectionTestResult;
import xiaowu.example.supplieretl.datasource.application.service.DataSourceConnectionApplicationService;
import xiaowu.example.supplieretl.datasource.application.service.DataSourceConnectionApplicationService.CreateConnectionCommand;
import xiaowu.example.supplieretl.datasource.application.service.DataSourceConnectionApplicationService.SupportedDataSourceType;
import xiaowu.example.supplieretl.datasource.application.service.DataSourceSecurityAuditApplicationService;
import xiaowu.example.supplieretl.datasource.application.service.DataSourceConnectionTestApplicationService;
import xiaowu.example.supplieretl.datasource.application.service.DataSourceConnectionTestApplicationService.TestConnectionCommand;
import xiaowu.example.supplieretl.datasource.application.service.ExcelConnectionTestApplicationService;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnection;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnectionTestLog;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceSecurityAuditLog;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.ExcelDataSourceConfig;
import xiaowu.example.supplieretl.datasource.security.AuditActionType;
import xiaowu.example.supplieretl.datasource.security.ConnectionSecurityAuditContext;
import xiaowu.example.supplieretl.datasource.security.ConnectionTestGuardService;
import xiaowu.example.supplieretl.datasource.security.ConnectionTestRateLimitException;
import xiaowu.example.supplieretl.datasource.security.SecurityAuditStatus;

@RestController
@RequestMapping("/api/examples/data-sources")
@RequiredArgsConstructor
@Tag(name = "Data Source Connection", description = "Generic data source connection management APIs")
public class DataSourceConnectionController {

  private final DataSourceConnectionApplicationService applicationService;
  private final DataSourceConnectionTestApplicationService connectionTestApplicationService;
  private final ExcelConnectionTestApplicationService excelConnectionTestApplicationService;
  private final DataSourceSecurityAuditApplicationService securityAuditApplicationService;
  private final ConnectionTestGuardService connectionTestGuardService;

  @GetMapping("/types")
  @Operation(summary = "List supported data source types")
  public List<SupportedDataSourceType> supportedTypes() {
    return applicationService.supportedTypes();
  }

  @GetMapping("/connections")
  @Operation(summary = "List data source connections")
  public List<DataSourceConnectionView> listConnections() {
    return applicationService.listConnections().stream()
        .map(DataSourceConnectionController::toView)
        .toList();
  }

  @GetMapping("/connections/{id}")
  @Operation(summary = "Get data source connection detail")
  public DataSourceConnectionView getConnection(@PathVariable Long id) {
    return toView(applicationService.getConnection(id));
  }

  @PostMapping("/connections")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create data source connection")
  public DataSourceConnectionView createConnection(@RequestBody CreateConnectionRequest request) {
    DataSourceConnection connection = applicationService.createConnection(new CreateConnectionCommand(
        request.connectionName(),
        request.description(),
        request.type(),
        request.config()));
    return toView(connection);
  }

  @PostMapping("/connections/test")
  @Operation(summary = "Test connection with transient config")
  public ConnectionTestView testConnection(
      @RequestBody TestConnectionRequest request,
      HttpServletRequest httpServletRequest) {
    ConnectionSecurityAuditContext auditContext = connectionTestGuardService.inspectTransient(
        httpServletRequest,
        request.type(),
        applicationService.parseConfig(request.type(), request.config()));
    try {
      connectionTestGuardService.enforce(auditContext);
      ConnectionTestResult result = connectionTestApplicationService.testConnection(new TestConnectionCommand(
          request.type(),
          request.config()));
      auditResult(AuditActionType.TRANSIENT_TEST, auditContext, result);
      return toTestView(result);
    } catch (RuntimeException ex) {
      auditFailure(AuditActionType.TRANSIENT_TEST, auditContext, ex);
      throw ex;
    }
  }

  @PostMapping("/connections/{id}/test")
  @Operation(summary = "Test saved connection and persist test log")
  public ConnectionTestView testSavedConnection(
      @PathVariable Long id,
      HttpServletRequest httpServletRequest) {
    DataSourceConnection connection = applicationService.getConnection(id);
    ConnectionSecurityAuditContext auditContext = connectionTestGuardService.inspectSaved(httpServletRequest, connection);
    try {
      connectionTestGuardService.enforce(auditContext);
      ConnectionTestResult result = connectionTestApplicationService.testSavedConnection(id);
      auditResult(AuditActionType.SAVED_TEST, auditContext, result);
      return toTestView(result);
    } catch (RuntimeException ex) {
      auditFailure(AuditActionType.SAVED_TEST, auditContext, ex);
      throw ex;
    }
  }

  @GetMapping("/connections/{id}/test-logs")
  @Operation(summary = "List recent connection test logs")
  public List<ConnectionTestLogView> recentLogs(
      @PathVariable Long id,
      @RequestParam(defaultValue = "10") int limit) {
    return connectionTestApplicationService.recentLogs(id, limit).stream()
        .map(DataSourceConnectionController::toLogView)
        .toList();
  }

  @PostMapping(value = "/excel/test", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary = "Test Excel parsing")
  public ConnectionTestView testExcel(
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "sheetName", required = false) String sheetName,
      @RequestParam(value = "headerRowIndex", required = false) Integer headerRowIndex,
      @RequestParam(value = "sampleSize", required = false) Integer sampleSize,
      HttpServletRequest httpServletRequest) {
    ConnectionSecurityAuditContext auditContext = connectionTestGuardService.inspectExcel(httpServletRequest);
    ExcelDataSourceConfig config = new ExcelDataSourceConfig(sheetName, headerRowIndex, sampleSize);
    try {
      connectionTestGuardService.enforce(auditContext);
      ConnectionTestResult result = excelConnectionTestApplicationService.test(file, config);
      auditResult(AuditActionType.EXCEL_TEST, auditContext, result);
      return toTestView(result);
    } catch (RuntimeException ex) {
      auditFailure(AuditActionType.EXCEL_TEST, auditContext, ex);
      throw ex;
    }
  }

  @GetMapping("/security-audit-logs")
  @Operation(summary = "List recent connection security audit logs")
  public List<SecurityAuditLogView> recentSecurityAuditLogs(
      @RequestParam(defaultValue = "20") int limit) {
    return securityAuditApplicationService.recent(limit).stream()
        .map(DataSourceConnectionController::toSecurityAuditView)
        .toList();
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, String> handleIllegalArgument(IllegalArgumentException ex) {
    return Map.of("message", ex.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Map<String, String> handleIllegalState(IllegalStateException ex) {
    return Map.of("message", ex.getMessage());
  }

  @ExceptionHandler(ConnectionTestRateLimitException.class)
  @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
  public Map<String, String> handleRateLimit(ConnectionTestRateLimitException ex) {
    return Map.of("message", ex.getMessage());
  }

  private static DataSourceConnectionView toView(DataSourceConnection connection) {
    return new DataSourceConnectionView(
        connection.getId(),
        connection.getConnectionName(),
        connection.getDescription(),
        connection.getType().name(),
        connection.getConfig().toMaskedMap(),
        connection.getCreatedAt(),
        connection.getUpdatedAt());
  }

  private static ConnectionTestView toTestView(ConnectionTestResult result) {
    return new ConnectionTestView(
        result.success(),
        result.message(),
        result.detail(),
        result.testedAt());
  }

  private static ConnectionTestLogView toLogView(DataSourceConnectionTestLog log) {
    return new ConnectionTestLogView(
        log.getId(),
        log.getConnectionId(),
        log.getDataSourceType().name(),
        log.isSuccess(),
        log.getMessage(),
        log.getDetail(),
        log.getTestedAt());
  }

  private static SecurityAuditLogView toSecurityAuditView(DataSourceSecurityAuditLog log) {
    return new SecurityAuditLogView(
        log.getId(),
        log.getAction().name(),
        log.getConnectionId(),
        log.getDataSourceType().name(),
        log.getActorId(),
        log.getClientIp(),
        log.getTargetSummary(),
        log.getResolvedAddresses(),
        log.isSuccess(),
        log.getStatus().name(),
        log.getMessage(),
        log.getDetail(),
        log.getCreatedAt());
  }

  private void auditResult(
      AuditActionType action,
      ConnectionSecurityAuditContext context,
      ConnectionTestResult result) {
    securityAuditApplicationService.record(
        action,
        context,
        result.success(),
        result.success() ? SecurityAuditStatus.SUCCESS : SecurityAuditStatus.FAILED,
        result.message(),
        result.detail());
  }

  private void auditFailure(
      AuditActionType action,
      ConnectionSecurityAuditContext context,
      RuntimeException ex) {
    SecurityAuditStatus status = ex instanceof ConnectionTestRateLimitException
        || ex instanceof IllegalArgumentException
            ? SecurityAuditStatus.BLOCKED
            : SecurityAuditStatus.FAILED;
    securityAuditApplicationService.record(
        action,
        context,
        false,
        status,
        ex.getMessage(),
        Map.of("errorType", ex.getClass().getSimpleName()));
  }

  public record CreateConnectionRequest(
      @Schema(description = "Unique connection name", example = "local-kafka") String connectionName,
      @Schema(description = "Connection description", example = "Local Kafka broker for ETL") String description,
      @Schema(description = "Data source type", example = "KAFKA") DataSourceType type,
      @Schema(description = "Type-specific config payload") JsonNode config) {
  }

  public record TestConnectionRequest(
      @Schema(description = "Data source type", example = "MYSQL") DataSourceType type,
      @Schema(description = "Type-specific config payload") JsonNode config) {
  }

  public record DataSourceConnectionView(
      Long id,
      String connectionName,
      String description,
      String type,
      Map<String, Object> maskedConfig,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
  }

  public record ConnectionTestView(
      boolean success,
      String message,
      Map<String, Object> detail,
      LocalDateTime testedAt) {
  }

  public record ConnectionTestLogView(
      Long id,
      Long connectionId,
      String dataSourceType,
      boolean success,
      String message,
      Map<String, Object> detail,
      LocalDateTime testedAt) {
  }

  public record SecurityAuditLogView(
      Long id,
      String action,
      Long connectionId,
      String dataSourceType,
      String actorId,
      String clientIp,
      String targetSummary,
      String resolvedAddresses,
      boolean success,
      String status,
      String message,
      Map<String, Object> detail,
      LocalDateTime createdAt) {
  }
}
