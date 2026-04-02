package xiaowu.example.supplieretl.interfaces.rest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
@ConditionalOnBean(SupplierPullSchedulingApplicationService.class)
public class SupplierSchedulingController {

    private final SupplierConnectionRepository supplierConnectionRepository;
    private final SupplierPullSchedulingApplicationService schedulingApplicationService;
    private final SupplierSchedulingProperties schedulingProperties;

    @GetMapping("/connections")
    public List<SupplierConnectionView> listConnections() {
        return supplierConnectionRepository.findAll().stream()
                .map(SupplierSchedulingController::toView)
                .toList();
    }

    @GetMapping("/connections/{supplierId}")
    public SupplierConnectionView getConnection(@PathVariable Long supplierId) {
        SupplierConnection connection = supplierConnectionRepository.findBySupplierId(supplierId)
                .orElseThrow(() -> new IllegalStateException("Supplier connection not found: " + supplierId));
        return toView(connection);
    }

    @PostMapping("/scheduling/dispatch")
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
    public Map<String, Object> demo() {
        return Map.of(
                "seedSuppliers", List.of(
                        Map.of("supplierId", 9001L, "supplierCode", "SUPPLIER_ALPHA"),
                        Map.of("supplierId", 9002L, "supplierCode", "SUPPLIER_FAIL_ONCE"),
                        Map.of("supplierId", 9003L, "supplierCode", "SUPPLIER_PAUSED")),
                "defaultSchedulingConfig", Map.of(
                        "fixedDelayMs", schedulingProperties.fixedDelayMs(),
                        "batchSize", schedulingProperties.batchSize(),
                        "leaseSeconds", schedulingProperties.leaseSeconds()),
                "manualDispatchExample", Map.of(
                        "requestPath", "/api/examples/suppliers/scheduling/dispatch",
                        "body", Map.of(
                                "batchSize", schedulingProperties.batchSize(),
                                "leaseSeconds", schedulingProperties.leaseSeconds())),
                "queryExamples", List.of(
                        "/api/examples/suppliers/connections",
                        "/api/examples/suppliers/connections/9001"));
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
            Integer batchSize,
            Long leaseSeconds) {
    }

    public record SupplierConnectionView(
            Long supplierId,
            String supplierCode,
            String status,
            int pullIntervalSeconds,
            LocalDateTime nextPullAt,
            LocalDateTime lastSuccessAt,
            LocalDateTime lastErrorAt,
            String lastCursor,
            int retryCount,
            LocalDateTime leaseUntil,
            long version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
