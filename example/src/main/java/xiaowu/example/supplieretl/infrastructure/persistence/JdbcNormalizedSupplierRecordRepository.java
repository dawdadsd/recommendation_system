package xiaowu.example.supplieretl.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import xiaowu.example.supplieretl.application.etl.model.NormalizedSupplierRecord;
import xiaowu.example.supplieretl.application.port.NormalizedSupplierRecordRepository;

/**
 * 标准化供应商结果表的 JDBC 实现。
 *
 * <p>通过“先更新、后插入、冲突后再尝试更新”的方式实现幂等 upsert，
 * 既兼容当前示例使用的 H2，也不把 SQL 强绑定到某个数据库方言。
 */
@Repository
@RequiredArgsConstructor
public class JdbcNormalizedSupplierRecordRepository implements NormalizedSupplierRecordRepository {

  private static final String UPDATE_SQL = """
      UPDATE supplier_normalized_record
      SET
          supplier_code = ?,
          source_business_code = ?,
          supplier_name = ?,
          source_supplier_status = ?,
          supplier_status = ?,
          tax_no = ?,
          source_modified_at = ?,
          page_token = ?,
          next_page_token = ?,
          last_pulled_at = ?,
          raw_item_json = ?,
          updated_at = ?
      WHERE supplier_id = ?
        AND erp_type = ?
        AND source_record_id = ?
        AND (last_pulled_at IS NULL OR last_pulled_at <= ?)
      """;

  private static final String INSERT_SQL = """
      INSERT INTO supplier_normalized_record (
          supplier_id,
          supplier_code,
          erp_type,
          source_record_id,
          source_business_code,
          supplier_name,
          source_supplier_status,
          supplier_status,
          tax_no,
          source_modified_at,
          page_token,
          next_page_token,
          last_pulled_at,
          raw_item_json,
          created_at,
          updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private final JdbcTemplate jdbcTemplate;

  @Override
  public UpsertSummary upsertAll(List<NormalizedSupplierRecord> records) {
    Objects.requireNonNull(records, "records");

    int upsertedCount = 0;
    int staleSkippedCount = 0;
    Instant now = Instant.now();

    for (NormalizedSupplierRecord record : records) {
      switch (upsertOne(record, now)) {
        case UPSERTED -> upsertedCount++;
        case STALE_SKIPPED -> staleSkippedCount++;
      }
    }

    return new UpsertSummary(upsertedCount, staleSkippedCount);
  }

  private WriteOutcome upsertOne(NormalizedSupplierRecord record, Instant now) {
    Timestamp pulledAt = Timestamp.from(record.pulledAt());
    Timestamp updatedAt = Timestamp.from(now);

    int updated = jdbcTemplate.update(
        UPDATE_SQL,
        record.supplierCode(),
        record.sourceBusinessCode(),
        record.supplierName(),
        record.sourceSupplierStatus(),
        record.supplierStatus().name(),
        record.taxNo(),
        record.sourceModifiedAt(),
        record.pageToken(),
        record.nextPageToken(),
        pulledAt,
        record.rawItemJson(),
        updatedAt,
        record.supplierId(),
        record.erpType(),
        record.sourceRecordId(),
        pulledAt);

    if (updated > 0) {
      return WriteOutcome.UPSERTED;
    }

    try {
      jdbcTemplate.update(
          INSERT_SQL,
          record.supplierId(),
          record.supplierCode(),
          record.erpType(),
          record.sourceRecordId(),
          record.sourceBusinessCode(),
          record.supplierName(),
          record.sourceSupplierStatus(),
          record.supplierStatus().name(),
          record.taxNo(),
          record.sourceModifiedAt(),
          record.pageToken(),
          record.nextPageToken(),
          pulledAt,
          record.rawItemJson(),
          updatedAt,
          updatedAt);
      return WriteOutcome.UPSERTED;
    } catch (DuplicateKeyException ex) {
      int retriedUpdate = jdbcTemplate.update(
          UPDATE_SQL,
          record.supplierCode(),
          record.sourceBusinessCode(),
          record.supplierName(),
          record.sourceSupplierStatus(),
          record.supplierStatus().name(),
          record.taxNo(),
          record.sourceModifiedAt(),
          record.pageToken(),
          record.nextPageToken(),
          pulledAt,
          record.rawItemJson(),
          updatedAt,
          record.supplierId(),
          record.erpType(),
          record.sourceRecordId(),
          pulledAt);
      return retriedUpdate > 0 ? WriteOutcome.UPSERTED : WriteOutcome.STALE_SKIPPED;
    }
  }

  private enum WriteOutcome {
    UPSERTED,
    STALE_SKIPPED
  }
}
