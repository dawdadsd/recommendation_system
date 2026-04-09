package xiaowu.example.supplieretl.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import xiaowu.example.supplieretl.application.etl.model.CanonicalSupplierStatus;
import xiaowu.example.supplieretl.application.etl.model.NormalizedSupplierRecord;

class JdbcNormalizedSupplierRecordRepositoryTest {

  private JdbcTemplate jdbcTemplate;
  private JdbcNormalizedSupplierRecordRepository repository;

  @BeforeEach
  void setUp() {
    String dbName = "supplier_normalized_" + UUID.randomUUID();
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "sa",
        "");
    dataSource.setDriverClassName("org.h2.Driver");

    jdbcTemplate = new JdbcTemplate(dataSource);
    repository = new JdbcNormalizedSupplierRecordRepository(jdbcTemplate);
    jdbcTemplate.execute("""
        CREATE TABLE supplier_normalized_record (
            id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
            supplier_id           BIGINT       NOT NULL,
            supplier_code         VARCHAR(64)  NOT NULL,
            erp_type              VARCHAR(16)  NOT NULL,
            source_record_id      VARCHAR(128) NOT NULL,
            source_business_code  VARCHAR(128) NOT NULL,
            supplier_name         VARCHAR(255) NOT NULL,
            source_supplier_status VARCHAR(64) NOT NULL,
            supplier_status       VARCHAR(64)  NOT NULL,
            tax_no                VARCHAR(64)  NOT NULL,
            source_modified_at    VARCHAR(64)  NOT NULL,
            page_token            VARCHAR(256) NOT NULL,
            next_page_token       VARCHAR(256) NOT NULL,
            last_pulled_at        TIMESTAMP    NOT NULL,
            raw_item_json         CLOB         NOT NULL,
            created_at            TIMESTAMP    NOT NULL,
            updated_at            TIMESTAMP    NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE UNIQUE INDEX uk_supplier_normalized_record_source
            ON supplier_normalized_record (supplier_id, erp_type, source_record_id)
        """);
  }

  @Test
  void upsertAllShouldInsertAndThenUpdateSameSourceRecord() {
    NormalizedSupplierRecord first = buildRecord(
        "Old Name",
        "B",
        CanonicalSupplierStatus.INACTIVE,
        Instant.parse("2026-04-09T01:00:00Z"));
    NormalizedSupplierRecord second = buildRecord(
        "New Name",
        "A",
        CanonicalSupplierStatus.ACTIVE,
        Instant.parse("2026-04-09T02:00:00Z"));

    var firstResult = repository.upsertAll(java.util.List.of(first));
    var secondResult = repository.upsertAll(java.util.List.of(second));

    assertThat(firstResult.upsertedCount()).isEqualTo(1);
    assertThat(secondResult.upsertedCount()).isEqualTo(1);

    Map<String, Object> row = jdbcTemplate.queryForMap("""
        SELECT supplier_name, supplier_status, source_supplier_status, source_business_code
        FROM supplier_normalized_record
        WHERE supplier_id = ? AND erp_type = ? AND source_record_id = ?
        """, 9101L, "KINGDEE", "FID-1001");

    assertThat(row.get("supplier_name")).isEqualTo("New Name");
    assertThat(row.get("source_supplier_status")).isEqualTo("A");
    assertThat(row.get("supplier_status")).isEqualTo("ACTIVE");
    assertThat(row.get("source_business_code")).isEqualTo("SUP-1001");
    assertThat(countRows()).isEqualTo(1);
  }

  @Test
  void upsertAllShouldSkipStaleEventWhenIncomingPulledAtIsOlder() {
    NormalizedSupplierRecord newer = buildRecord(
        "Current Name",
        "A",
        CanonicalSupplierStatus.ACTIVE,
        Instant.parse("2026-04-09T03:00:00Z"));
    NormalizedSupplierRecord older = buildRecord(
        "Stale Name",
        "B",
        CanonicalSupplierStatus.INACTIVE,
        Instant.parse("2026-04-09T02:00:00Z"));

    repository.upsertAll(java.util.List.of(newer));
    var result = repository.upsertAll(java.util.List.of(older));

    assertThat(result.upsertedCount()).isZero();
    assertThat(result.staleSkippedCount()).isEqualTo(1);

    Map<String, Object> row = jdbcTemplate.queryForMap("""
        SELECT supplier_name, supplier_status, source_supplier_status
        FROM supplier_normalized_record
        WHERE supplier_id = ? AND erp_type = ? AND source_record_id = ?
        """, 9101L, "KINGDEE", "FID-1001");

    assertThat(row.get("supplier_name")).isEqualTo("Current Name");
    assertThat(row.get("source_supplier_status")).isEqualTo("A");
    assertThat(row.get("supplier_status")).isEqualTo("ACTIVE");
    assertThat(countRows()).isEqualTo(1);
  }

  private long countRows() {
    Long count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM supplier_normalized_record",
        Long.class);
    return count == null ? 0L : count;
  }

  private NormalizedSupplierRecord buildRecord(
      String supplierName,
      String sourceSupplierStatus,
      CanonicalSupplierStatus supplierStatus,
      Instant pulledAt) {
    return new NormalizedSupplierRecord(
        9101L,
        "KD_SUPPLIER_HANGZHOU",
        "KINGDEE",
        "FID-1001",
        "SUP-1001",
        supplierName,
        sourceSupplierStatus,
        supplierStatus,
        "",
        "2026-04-09 09:30:00",
        "cursor-1",
        "cursor-2",
        pulledAt,
        "{\"id\":\"FID-1001\"}");
  }
}
