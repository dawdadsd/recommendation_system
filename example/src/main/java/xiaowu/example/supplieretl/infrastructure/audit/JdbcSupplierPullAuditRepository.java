package xiaowu.example.supplieretl.infrastructure.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link SupplierPullAuditRepository} 的 JDBC 实现，操作：
 * <ul>
 * <li>{@code supplier_pull_idempotency} — 幂等去重表</li>
 * <li>{@code supplier_pull_audit} — 审计流水表</li>
 * </ul>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcSupplierPullAuditRepository implements SupplierPullAuditRepository {

  private static final String INSERT_IDEMPOTENCY_SQL = """
      INSERT INTO supplier_pull_idempotency (supplier_id, idempotency_key, created_at)
      VALUES (?, ?, ?)
      """;

  private static final String INSERT_AUDIT_SQL = """
      INSERT INTO supplier_pull_audit (
          supplier_id, supplier_code, erp_type, outcome,
          record_count, error_kind, error_message,
          duration_ms, executed_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String FIND_LATEST_SQL = """
      SELECT supplier_id, supplier_code, erp_type, outcome,
             record_count, error_kind, error_message,
             duration_ms, executed_at
      FROM supplier_pull_audit
      WHERE supplier_id = ?
      ORDER BY executed_at DESC
      LIMIT 1
      """;

  private final JdbcTemplate jdbcTemplate;

  @Override
  public boolean tryInsertIdempotency(long supplierId, String idempotencyKey) {
    try {
      jdbcTemplate.update(INSERT_IDEMPOTENCY_SQL,
          supplierId, idempotencyKey, Timestamp.from(Instant.now()));
      return true;
    } catch (DuplicateKeyException ex) {
      log.debug("[Audit] idempotency hit supplierId={} key={}", supplierId, idempotencyKey);
      return false;
    }
  }

  @Override
  public void insertAudit(AuditEntry entry) {
    try {
      jdbcTemplate.update(INSERT_AUDIT_SQL,
          entry.supplierId(),
          entry.supplierCode(),
          entry.erpType(),
          entry.outcome(),
          entry.recordCount(),
          entry.errorKind(),
          entry.errorMessage(),
          entry.durationMs(),
          Timestamp.from(entry.executedAt()));
    } catch (Exception ex) {
      // 审计写入失败不阻断主流程，但必须记录 ERROR
      log.error("[Audit] CRITICAL: audit write failed supplierId={}", entry.supplierId(), ex);
    }
  }

  @Override
  public Optional<AuditEntry> findLatest(long supplierId) {
    try {
      AuditEntry entry = jdbcTemplate.queryForObject(
          FIND_LATEST_SQL, this::mapRow, supplierId);
      return Optional.ofNullable(entry);
    } catch (EmptyResultDataAccessException ex) {
      return Optional.empty();
    }
  }

  private AuditEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new AuditEntry(
        rs.getLong("supplier_id"),
        rs.getString("supplier_code"),
        rs.getString("erp_type"),
        rs.getString("outcome"),
        rs.getInt("record_count"),
        rs.getString("error_kind"),
        rs.getString("error_message"),
        rs.getLong("duration_ms"),
        toInstant(rs.getTimestamp("executed_at")));
  }

  private static Instant toInstant(Timestamp ts) {
    return ts != null ? ts.toInstant() : Instant.now();
  }
}
