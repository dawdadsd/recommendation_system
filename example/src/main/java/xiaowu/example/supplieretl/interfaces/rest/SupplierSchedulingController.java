package xiaowu.example.supplieretl.interfaces.rest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import xiaowu.example.supplieretl.application.service.SupplierPullSchedulingApplicationService;
import xiaowu.example.supplieretl.application.service.SupplierPullSchedulingApplicationService.ScheduleCommand;
import xiaowu.example.supplieretl.application.service.SupplierPullSchedulingApplicationService.SchedulingResult;
import xiaowu.example.supplieretl.domain.entity.SupplierConnection;
import xiaowu.example.supplieretl.domain.repository.SupplierConnectionRepository;
import xiaowu.example.supplieretl.infrastructure.config.SupplierSchedulingProperties;

@RestController
@RequestMapping("/api/examples/suppliers")
@RequiredArgsConstructor
@Tag(name = "Supplier ETL Scheduling", description = "Supplier connection scheduling demo APIs")
public class SupplierSchedulingController {

        private final SupplierConnectionRepository supplierConnectionRepository;
        private final SupplierPullSchedulingApplicationService schedulingApplicationService;
        private final SupplierSchedulingProperties schedulingProperties;

        @GetMapping("/connections")
        @Operation(summary = "查看全部供应商连接状态", description = "返回当前 supplier_connection 表中的全部连接状态，便于观察 nextPullAt、retryCount、leaseUntil 等调度字段。")
        public List<SupplierConnectionView> listConnections() {
                return supplierConnectionRepository.findAll().stream()
                                .map(SupplierSchedulingController::toView)
                                .toList();
        }

        @GetMapping("/connections/{supplierId}")
        @Operation(summary = "查看单个供应商连接状态", description = "根据 supplierId 查询单个供应商连接的调度状态。")
        public SupplierConnectionView getConnection(
                        @Parameter(description = "供应商连接 ID", example = "9001") @PathVariable Long supplierId) {
                SupplierConnection connection = supplierConnectionRepository.findBySupplierId(supplierId)
                                .orElseThrow(() -> new IllegalStateException(
                                                "Supplier connection not found: " + supplierId));
                return toView(connection);
        }

        @PostMapping("/scheduling/dispatch")
        @Operation(summary = "手动触发一次调度", description = "立即执行一次到期连接扫描、租约抢占和 Kafka 任务发布，无需等待后台定时器。")
        public SchedulingResult dispatchOnce(@RequestBody(required = false) DispatchRequest request) {
                int batchSize = request == null || request.batchSize() == null
                                ? schedulingProperties.batchSize()
                                : request.batchSize();
                long leaseSeconds = request == null || request.leaseSeconds() == null
                                ? schedulingProperties.leaseSeconds()
                                : request.leaseSeconds();

                return schedulingApplicationService.scheduleDueConnections(
                                new ScheduleCommand(batchSize, leaseSeconds));
        }

        @GetMapping("/demo")
        @Operation(summary = "查看 supplieretl 演示样例", description = "返回种子数据、ERP接入说明、手动 dispatch 示例，方便在 Swagger UI 中快速上手。")
        public Map<String, Object> demo() {
                return Map.of(
                                "genericSuppliers", List.of(
                                                Map.of("supplierId", 9001L, "supplierCode", "SUPPLIER_ALPHA", "erpType",
                                                                "GENERIC"),
                                                Map.of("supplierId", 9002L, "supplierCode", "SUPPLIER_FAIL_ONCE",
                                                                "erpType", "GENERIC"),
                                                Map.of("supplierId", 9003L, "supplierCode", "SUPPLIER_PAUSED",
                                                                "erpType", "GENERIC")),
                                "kingdeeSuppliers（金蝶云星空）", List.of(
                                                Map.of("supplierId", 9101L, "supplierCode", "KD_SUPPLIER_HANGZHOU",
                                                                "erpType", "KINGDEE", "note",
                                                                "sandbox=true，切换sandbox=false并填写真实凭证接入金蝶"),
                                                Map.of("supplierId", 9102L, "supplierCode", "KD_SUPPLIER_SHENZHEN",
                                                                "erpType", "KINGDEE"),
                                                Map.of("supplierId", 9103L, "supplierCode", "KD_SUPPLIER_SUSPENDED",
                                                                "erpType", "KINGDEE", "status",
                                                                "SUSPENDED，模拟鉴权失败自动挂起")),
                                "yonyouSuppliers（用友BIP）", List.of(
                                                Map.of("supplierId", 9201L, "supplierCode", "YY_SUPPLIER_BEIJING",
                                                                "erpType", "YONYOU", "note",
                                                                "sandbox=true，切换sandbox=false并填写真实凭证接入用友"),
                                                Map.of("supplierId", 9202L, "supplierCode", "YY_SUPPLIER_SHANGHAI",
                                                                "erpType", "YONYOU", "note", "模拟被限流后等待重试")),
                                "erpKeyConfig", Map.of(
                                                "金蝶",
                                                "supplier.erp.kingdee.sandbox=false + base-url/acct-id/username/password",
                                                "用友",
                                                "supplier.erp.yonyou.sandbox=false + base-url/app-id/app-secret/corp-code",
                                                "注意", "密码须为 MD5 小写摘要（金蝶官方要求）"),
                                "defaultSchedulingConfig", Map.of(
                                                "fixedDelayMs", schedulingProperties.fixedDelayMs(),
                                                "batchSize", schedulingProperties.batchSize(),
                                                "leaseSeconds", schedulingProperties.leaseSeconds()),
                                "manualDispatchExample", Map.of(
                                                "requestPath", "/api/examples/suppliers/scheduling/dispatch",
                                                "body", Map.of("batchSize", schedulingProperties.batchSize(),
                                                                "leaseSeconds", schedulingProperties.leaseSeconds())),
                                "queryExamples", List.of(
                                                "/api/examples/suppliers/connections",
                                                "/api/examples/suppliers/connections/9101",
                                                "/api/examples/suppliers/connections/9201"));
        }

        @ExceptionHandler(IllegalStateException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        public Map<String, String> handleIllegalState(IllegalStateException ex) {
                return Map.of("message", ex.getMessage());
        }

        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Map<String, String> handleIllegalArgument(IllegalArgumentException ex) {
                return Map.of("message", ex.getMessage());
        }

        private static SupplierConnectionView toView(SupplierConnection connection) {
                return new SupplierConnectionView(
                                connection.getSupplierId(),
                                connection.getSupplierCode(),
                                connection.getStatus().name(),
                                connection.getPullIntervalSeconds(),
                                connection.getNextPullAt(),
                                connection.getLastSuccessAt(),
                                connection.getLastErrorAt(),
                                connection.getLastCursor(),
                                connection.getRetryCount(),
                                connection.getLeaseUntil(),
                                connection.getVersion(),
                                connection.getCreatedAt(),
                                connection.getUpdatedAt());
        }

        public record DispatchRequest(
                        @Schema(description = "本次手动调度扫描的最大连接数", example = "10") Integer batchSize,
                        @Schema(description = "租约持续时间，单位秒", example = "30") Long leaseSeconds) {
        }

        public record SupplierConnectionView(
                        @Schema(description = "供应商连接 ID", example = "9001") Long supplierId,
                        @Schema(description = "供应商编码", example = "SUPPLIER_ALPHA") String supplierCode,
                        @Schema(description = "连接状态", example = "ACTIVE") String status,
                        @Schema(description = "拉取间隔，单位秒", example = "60") int pullIntervalSeconds,
                        @Schema(description = "下一次允许被调度的时间点") LocalDateTime nextPullAt,
                        @Schema(description = "最近一次拉取成功时间") LocalDateTime lastSuccessAt,
                        @Schema(description = "最近一次拉取失败时间") LocalDateTime lastErrorAt,
                        @Schema(description = "最近一次增量游标", example = "cursor-9001-v1") String lastCursor,
                        @Schema(description = "连续失败重试次数", example = "0") int retryCount,
                        @Schema(description = "当前租约到期时间") LocalDateTime leaseUntil,
                        @Schema(description = "乐观锁版本号", example = "3") long version,
                        @Schema(description = "创建时间") LocalDateTime createdAt,
                        @Schema(description = "最后更新时间") LocalDateTime updatedAt) {
        }
}
