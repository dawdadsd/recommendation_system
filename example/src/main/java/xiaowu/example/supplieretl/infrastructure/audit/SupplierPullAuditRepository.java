package xiaowu.example.supplieretl.infrastructure.audit;

import java.time.Instant;
import java.util.Optional;

/**
 * 供应商拉取幂等表 + 审计流水的数据访问接口。
 *
 * <h2>幂等表（supplier_pull_idempotency）</h2>
 * <p>
 * 在执行服务真正调用 ERP 之前先插入幂等记录，
 * 重复投递的任务（Kafka at-least-once）会因唯一索引冲突被拦截，避免重复拉取和重复计费。
 *
 * <h2>审计流水（supplier_pull_audit）</h2>
 * <p>
 * 每次拉取（无论成功 / 失败）写一条审计日志，保留原始错误信息，
 * 方便事后溯源、SLA 统计和人工复核。
 */
public interface SupplierPullAuditRepository {

  /**
   * 尝试插入幂等记录。
   *
   * @param supplierId     供应商 ID
   * @param idempotencyKey 幂等键（建议用 supplierId + 调度轮次时间截断到分钟）
   * @return true = 插入成功（可以执行），false = 已存在（重复投递，跳过）
   */
  boolean tryInsertIdempotency(long supplierId, String idempotencyKey);

  /**
   * 插入一条审计流水。
   *
   * @param entry 审计记录
   */
  void insertAudit(AuditEntry entry);

  /**
   * 查询最近一次拉取的审计记录（可选）。
   *
   * @param supplierId 供应商 ID
   * @return 最近一条审计记录，不存在时返回 empty
   */
  Optional<AuditEntry> findLatest(long supplierId);

  /**
   * @param supplierId   供应商 ID
   * @param supplierCode 供应商编码
   * @param erpType      ERP 类型标签
   * @param outcome      拉取结果：SUCCESS / FAILURE / SKIPPED_DUPLICATE / CIRCUIT_OPEN
   * @param recordCount  本次拉取的记录数，失败时为 0
   * @param errorKind    失败分类（来自 FailureKind.name()），成功时为 null
   * @param errorMessage 失败详情，成功时为 null
   * @param durationMs   本次拉取耗时（毫秒）
   * @param executedAt   执行时间戳
   */
  record AuditEntry(
      long supplierId,
      String supplierCode,
      String erpType,
      String outcome,
      int recordCount,
      String errorKind,
      String errorMessage,
      long durationMs,
      Instant executedAt) {
  }
}
