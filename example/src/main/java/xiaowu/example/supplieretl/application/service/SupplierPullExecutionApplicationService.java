package xiaowu.example.supplieretl.application.service;

import java.time.LocalDateTime;
import java.util.Objects;

import xiaowu.example.supplieretl.application.port.SupplierPullClient;
import xiaowu.example.supplieretl.application.port.SupplierPullClient.PullCommand;
import xiaowu.example.supplieretl.domain.entity.SupplierConnection;
import xiaowu.example.supplieretl.domain.repository.SupplierConnectionRepository;

/**
 * 调度单个供应商拉取任务后执行该任务。
 */
public class SupplierPullExecutionApplicationService {

    private final SupplierConnectionRepository supplierConnectionRepository;
    private final SupplierPullClient supplierPullClient;

    public SupplierPullExecutionApplicationService(
            SupplierConnectionRepository supplierConnectionRepository,
            SupplierPullClient supplierPullClient) {
        this.supplierConnectionRepository = Objects.requireNonNull(
                supplierConnectionRepository,
                "supplierConnectionRepository");
        this.supplierPullClient = Objects.requireNonNull(supplierPullClient, "supplierPullClient");
    }

    public ExecutionResult execute(ExecuteCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.retryDelaySeconds() <= 0) {
            throw new IllegalArgumentException("retryDelaySeconds must be positive");
        }

        SupplierConnection connection = supplierConnectionRepository.findBySupplierId(command.supplierId())
                .orElseThrow(() -> new IllegalStateException(
                        "Supplier connection not found: " + command.supplierId()));

        long expectedVersion = connection.getVersion();

        try {
            var pullResult = supplierPullClient.pull(new PullCommand(
                    connection.getSupplierId(),
                    connection.getSupplierCode(),
                    connection.getLastCursor(),
                    connection.getRetryCount()));

            LocalDateTime pulledAt = Objects.requireNonNullElseGet(
                    pullResult.pulledAt(),
                    LocalDateTime::now);
            connection.markPullSucceeded(
                    pullResult.nextCursor(),
                    pulledAt,
                    connection.calculateNextPullAtFrom(pulledAt));

            boolean updated = supplierConnectionRepository.markPullSuccess(
                    connection.getSupplierId(),
                    expectedVersion,
                    connection.getLastCursor(),
                    connection.getLastSuccessAt(),
                    connection.getNextPullAt(),
                    connection.getUpdatedAt());

            if (!updated) {
                throw new IllegalStateException(
                        "Failed to persist supplier pull success for supplierId=" + connection.getSupplierId());
            }

            return new ExecutionResult(
                    connection.getSupplierId(),
                    true,
                    pullResult.recordCount(),
                    connection.getLastCursor(),
                    null);
        } catch (RuntimeException ex) {
            LocalDateTime errorAt = LocalDateTime.now();
            connection.markPullFailed(
                    errorAt,
                    connection.calculateRetryAtFrom(errorAt, command.retryDelaySeconds()));

            boolean updated = supplierConnectionRepository.markPullFailure(
                    connection.getSupplierId(),
                    expectedVersion,
                    connection.getLastErrorAt(),
                    connection.getNextPullAt(),
                    connection.getRetryCount(),
                    connection.getUpdatedAt());

            if (!updated) {
                throw new IllegalStateException(
                        "Failed to persist supplier pull failure for supplierId=" + connection.getSupplierId(),
                        ex);
            }

            return new ExecutionResult(
                    connection.getSupplierId(),
                    false,
                    0,
                    connection.getLastCursor(),
                    ex.getMessage());
        }
    }

    public record ExecuteCommand(
            Long supplierId,
            int retryDelaySeconds) {
    }

    /**
     * 供应商拉取任务的执行结果，包含供应商ID、执行是否成功、拉取记录数、最后游标和错误信息（如果有）。
     */
    public record ExecutionResult(
            Long supplierId,
            boolean success,
            int recordCount,
            String lastCursor,
            String errorMessage) {
    }
}
