package xiaowu.example.supplieretl.application.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
import xiaowu.example.supplieretl.application.port.RawDataPublisher;
import xiaowu.example.supplieretl.application.port.RawDataPublisher.DlqEvent;
import xiaowu.example.supplieretl.application.port.RawDataPublisher.RawDataEvent;
import xiaowu.example.supplieretl.application.port.SupplierFetchException;
import xiaowu.example.supplieretl.application.port.SupplierPullClient;
import xiaowu.example.supplieretl.application.port.SupplierPullClient.PullCommand;
import xiaowu.example.supplieretl.domain.entity.SupplierConnection;
import xiaowu.example.supplieretl.domain.repository.SupplierConnectionRepository;
import xiaowu.example.supplieretl.infrastructure.audit.SupplierPullAuditRepository;
import xiaowu.example.supplieretl.infrastructure.audit.SupplierPullAuditRepository.AuditEntry;

/**
 * 调度单个供应商拉取任务后执行该任务。
 *
 * <h2>完整执行流程</h2>
 * 
 * <pre>
 *  1. 幂等检查（supplier_pull_idempotency），重复投递直接返回 SKIPPED
 *  2. 从 DB 加载 SupplierConnection（乐观锁版本）
 *  3. 调用路由客户端 → [KingdeeAdapter | YonyouAdapter | Fallback]
 *     内含：熔断器 + per-supplier 并发隔离
 *  4a. 成功 → markPullSuccess + 发布原始数据到 supplier.raw.data
 *  4b. 失败（分类处理）：
 *       AUTH_FAILURE  → markSuspended（需人工修复凭证）
 *       RATE_LIMITED  → 按 retryAfter 或指数退避计算 nextPullAt
 *       TIMEOUT       → 指数退避
 *       DATA_ERROR    → 发 DLQ + 指数退避
 *       UNAVAILABLE   → 指数退避（熔断已计数）
 *  5. 写审计流水（supplier_pull_audit）
 * </pre>
 */
@Slf4j
public class SupplierPullExecutionApplicationService {

        private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

        /** ERP 类型标签：按供应商编码前缀决定，传入 Kafka 消息让 Spark 选择解析器。 */
        private static final String ERP_KINGDEE = "KINGDEE";
        private static final String ERP_YONYOU = "YONYOU";
        private static final String ERP_GENERIC = "GENERIC";

        private final SupplierConnectionRepository supplierConnectionRepository;
        private final SupplierPullClient supplierPullClient;
        private final RawDataPublisher rawDataPublisher;
        private final SupplierPullAuditRepository auditRepository;

        public SupplierPullExecutionApplicationService(
                        SupplierConnectionRepository supplierConnectionRepository,
                        SupplierPullClient supplierPullClient,
                        RawDataPublisher rawDataPublisher,
                        SupplierPullAuditRepository auditRepository) {
                this.supplierConnectionRepository = Objects.requireNonNull(
                                supplierConnectionRepository, "supplierConnectionRepository");
                this.supplierPullClient = Objects.requireNonNull(supplierPullClient, "supplierPullClient");
                this.rawDataPublisher = Objects.requireNonNull(rawDataPublisher, "rawDataPublisher");
                this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository");
        }

        public ExecutionResult execute(ExecuteCommand command) {
                Objects.requireNonNull(command, "command");
                if (command.retryDelaySeconds() <= 0) {
                        throw new IllegalArgumentException("retryDelaySeconds must be positive");
                }

                long startMs = System.currentTimeMillis();

                SupplierConnection connection = supplierConnectionRepository
                                .findBySupplierId(command.supplierId())
                                .orElseThrow(() -> new IllegalStateException(
                                                "Supplier connection not found: " + command.supplierId()));

                // ── 1. 幂等检查 ────────────────────────────────────────────────────────
                String idempotencyKey = command.supplierId() + "_" + LocalDateTime.now().format(MINUTE_FMT);
                if (!auditRepository.tryInsertIdempotency(command.supplierId(), idempotencyKey)) {
                        log.info("[Execution] supplierId={} duplicate task skipped", command.supplierId());
                        writeAudit(connection, "SKIPPED_DUPLICATE", 0, null, null, 0);
                        return new ExecutionResult(command.supplierId(), true, 0, connection.getLastCursor(),
                                        "Skipped: duplicate task within the same minute");
                }

                long expectedVersion = connection.getVersion();
                String erpType = resolveErpType(connection.getSupplierCode());

                // ── 2. 执行拉取 ───────────────────────────────────────────────────────
                try {
                        var pullResult = supplierPullClient.pull(new PullCommand(
                                        connection.getSupplierId(),
                                        connection.getSupplierCode(),
                                        connection.getLastCursor(),
                                        connection.getRetryCount()));

                        LocalDateTime pulledAt = Objects.requireNonNullElseGet(
                                        pullResult.pulledAt(), LocalDateTime::now);

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
                                                "Optimistic lock conflict on success update supplierId="
                                                                + command.supplierId());
                        }

                        // ── 3. 发布原始数据到 Kafka ──────────────────────────────────────
                        rawDataPublisher.publish(new RawDataEvent(
                                        connection.getSupplierId(),
                                        connection.getSupplierCode(),
                                        erpType,
                                        buildRawPayload(connection, pullResult),
                                        connection.getLastCursor(),
                                        pullResult.nextCursor(),
                                        pulledAt.toInstant(ZoneOffset.UTC),
                                        pullResult.recordCount()));

                        long durationMs = System.currentTimeMillis() - startMs;
                        writeAudit(connection, "SUCCESS", pullResult.recordCount(), null, null, durationMs);

                        log.info("[Execution] supplierId={} SUCCESS recordCount={} nextCursor={} durationMs={}",
                                        connection.getSupplierId(), pullResult.recordCount(),
                                        pullResult.nextCursor(), durationMs);

                        return new ExecutionResult(
                                        connection.getSupplierId(), true, pullResult.recordCount(),
                                        connection.getLastCursor(), null);

                } catch (SupplierFetchException ex) {
                        return handleFetchFailure(command, connection, expectedVersion, ex,
                                        System.currentTimeMillis() - startMs);
                } catch (RuntimeException ex) {
                        long durationMs = System.currentTimeMillis() - startMs;
                        return handleGenericFailure(command, connection, expectedVersion, ex, durationMs);
                }
        }

        // ─── 失败分类处理 ─────────────────────────────────────────────────────────────

        private ExecutionResult handleFetchFailure(
                        ExecuteCommand command,
                        SupplierConnection connection,
                        long expectedVersion,
                        SupplierFetchException ex,
                        long durationMs) {

                LocalDateTime errorAt = LocalDateTime.now();
                String erpType = resolveErpType(connection.getSupplierCode());

                switch (ex.kind()) {

                        case AUTH_FAILURE -> {
                                // 鉴权失败 → 挂起，不再自动重试，等人工介入
                                supplierConnectionRepository.markSuspended(
                                                connection.getSupplierId(), expectedVersion, errorAt, errorAt);
                                writeAudit(connection, "FAILURE", 0, "AUTH_FAILURE", ex.getMessage(), durationMs);
                                log.error("[Execution] supplierId={} AUTH_FAILURE → SUSPENDED, manual fix required",
                                                connection.getSupplierId());
                                return new ExecutionResult(command.supplierId(), false, 0,
                                                connection.getLastCursor(), "AUTH_FAILURE: " + ex.getMessage());
                        }

                        case RATE_LIMITED -> {
                                // 限流：优先用 ERP 返回的 retryAfter，否则退级到指数退避
                                LocalDateTime retryAt = computeRateLimitRetryAt(
                                                (SupplierFetchException.RateLimitedException) ex,
                                                connection, command, errorAt);
                                connection.markPullFailed(errorAt, retryAt);
                                persistFailure(connection, expectedVersion);
                                writeAudit(connection, "FAILURE", 0, "RATE_LIMITED", ex.getMessage(), durationMs);
                                log.warn("[Execution] supplierId={} RATE_LIMITED retryAt={}", command.supplierId(),
                                                retryAt);
                                return new ExecutionResult(command.supplierId(), false, 0,
                                                connection.getLastCursor(), "RATE_LIMITED");
                        }

                        case DATA_ERROR -> {
                                // 数据异常 → 发 DLQ + 指数退避
                                SupplierFetchException.DataException dataEx = (SupplierFetchException.DataException) ex;
                                rawDataPublisher.publishDlq(new DlqEvent(
                                                connection.getSupplierId(), connection.getSupplierCode(), erpType,
                                                dataEx.rawSnippet(), "DATA_ERROR", ex.getMessage(), Instant.now()));
                                LocalDateTime retryAt = connection.calculateRetryAtWithBackoff(
                                                errorAt, connection.getRetryCount(),
                                                command.retryBackoffBaseSeconds(),
                                                command.retryBackoffMaxSeconds(),
                                                command.retryBackoffMaxJitterMs());
                                connection.markPullFailed(errorAt, retryAt);
                                persistFailure(connection, expectedVersion);
                                writeAudit(connection, "FAILURE", 0, "DATA_ERROR", ex.getMessage(), durationMs);
                                log.warn("[Execution] supplierId={} DATA_ERROR → DLQ", command.supplierId());
                                return new ExecutionResult(command.supplierId(), false, 0,
                                                connection.getLastCursor(), "DATA_ERROR");
                        }

                        // TIMEOUT + UNAVAILABLE：统一走指数退避
                        default -> {
                                LocalDateTime retryAt = connection.calculateRetryAtWithBackoff(
                                                errorAt, connection.getRetryCount(),
                                                command.retryBackoffBaseSeconds(),
                                                command.retryBackoffMaxSeconds(),
                                                command.retryBackoffMaxJitterMs());
                                connection.markPullFailed(errorAt, retryAt);
                                persistFailure(connection, expectedVersion);
                                writeAudit(connection, "FAILURE", 0, ex.kind().name(), ex.getMessage(), durationMs);
                                log.warn("[Execution] supplierId={} {} retryAt={}",
                                                command.supplierId(), ex.kind(), retryAt);
                                return new ExecutionResult(command.supplierId(), false, 0,
                                                connection.getLastCursor(), ex.kind() + ": " + ex.getMessage());
                        }
                }
        }

        private ExecutionResult handleGenericFailure(
                        ExecuteCommand command,
                        SupplierConnection connection,
                        long expectedVersion,
                        RuntimeException ex,
                        long durationMs) {

                LocalDateTime errorAt = LocalDateTime.now();
                LocalDateTime retryAt = connection.calculateRetryAtWithBackoff(
                                errorAt, connection.getRetryCount(),
                                command.retryBackoffBaseSeconds(),
                                command.retryBackoffMaxSeconds(),
                                command.retryBackoffMaxJitterMs());
                connection.markPullFailed(errorAt, retryAt);
                persistFailure(connection, expectedVersion);
                writeAudit(connection, "FAILURE", 0, "UNKNOWN", ex.getMessage(), durationMs);
                log.error("[Execution] supplierId={} unexpected error", command.supplierId(), ex);
                return new ExecutionResult(command.supplierId(), false, 0,
                                connection.getLastCursor(), ex.getMessage());
        }

        // ─── 工具方法 ─────────────────────────────────────────────────────────────────

        private static String resolveErpType(String supplierCode) {
                if (supplierCode.startsWith("KD_"))
                        return ERP_KINGDEE;
                if (supplierCode.startsWith("YY_"))
                        return ERP_YONYOU;
                return ERP_GENERIC;
        }

        private static String buildRawPayload(SupplierConnection conn, SupplierPullClient.PullResult result) {
                return "{\"supplierId\":" + conn.getSupplierId()
                                + ",\"supplierCode\":\"" + conn.getSupplierCode() + "\""
                                + ",\"recordCount\":" + result.recordCount()
                                + ",\"nextCursor\":\"" + (result.nextCursor() == null ? "" : result.nextCursor()) + "\""
                                + ",\"pulledAt\":\"" + result.pulledAt() + "\"}";
        }

        private LocalDateTime computeRateLimitRetryAt(
                        SupplierFetchException.RateLimitedException ex,
                        SupplierConnection connection,
                        ExecuteCommand command,
                        LocalDateTime errorAt) {
                if (ex.retryAfter() != null) {
                        return LocalDateTime.ofInstant(ex.retryAfter(), ZoneOffset.UTC);
                }
                return connection.calculateRetryAtWithBackoff(
                                errorAt, connection.getRetryCount(),
                                command.retryBackoffBaseSeconds(),
                                command.retryBackoffMaxSeconds(),
                                command.retryBackoffMaxJitterMs());
        }

        private void persistFailure(SupplierConnection connection, long expectedVersion) {
                boolean updated = supplierConnectionRepository.markPullFailure(
                                connection.getSupplierId(),
                                expectedVersion,
                                connection.getLastErrorAt(),
                                connection.getNextPullAt(),
                                connection.getRetryCount(),
                                connection.getUpdatedAt());
                if (!updated) {
                        log.warn("[Execution] supplierId={} optimistic lock conflict on failure persist",
                                        connection.getSupplierId());
                }
        }

        private void writeAudit(
                        SupplierConnection conn,
                        String outcome,
                        int recordCount,
                        String errorKind,
                        String errorMessage,
                        long durationMs) {
                try {
                        auditRepository.insertAudit(new AuditEntry(
                                        conn.getSupplierId(),
                                        conn.getSupplierCode(),
                                        resolveErpType(conn.getSupplierCode()),
                                        outcome,
                                        recordCount,
                                        errorKind,
                                        errorMessage,
                                        durationMs,
                                        Instant.now()));
                } catch (Exception ex) {
                        log.error("[Execution] audit write failed supplierId={}", conn.getSupplierId(), ex);
                }
        }

        // ─── 命令 / 结果记录 ──────────────────────────────────────────────────────────

        public record ExecuteCommand(
                        Long supplierId,
                        int retryDelaySeconds,
                        int retryBackoffBaseSeconds,
                        int retryBackoffMaxSeconds,
                        int retryBackoffMaxJitterMs) {
        }

        /** 供应商拉取任务的执行结果。 */
        public record ExecutionResult(
                        Long supplierId,
                        boolean success,
                        int recordCount,
                        String lastCursor,
                        String errorMessage) {
        }
}
