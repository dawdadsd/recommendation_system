package xiaowu.example.supplieretl.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import xiaowu.example.supplieretl.domain.entity.SupplierConnection;
import xiaowu.example.supplieretl.domain.entity.SupplierConnection.ConnectionStatus;
import xiaowu.example.supplieretl.domain.repository.SupplierConnectionRepository;

@Repository
@RequiredArgsConstructor
public class JdbcSupplierConnectionRepository implements SupplierConnectionRepository {

  private static final String FIND_BY_SUPPLIER_ID_SQL = """
      SELECT
          supplier_id,
          supplier_code,
          status,
          pull_interval_seconds,
          next_pull_at,
          last_success_at,
          last_error_at,
          last_cursor,
          retry_count,
          lease_until,
          version,
          created_at,
          updated_at
      FROM supplier_connection
      WHERE supplier_id = ?
      """;

  private static final String FIND_SCHEDULABLE_CONNECTIONS_SQL = """
      SELECT
          supplier_id,
          supplier_code,
          status,
          pull_interval_seconds,
          next_pull_at,
          last_success_at,
          last_error_at,
          last_cursor,
          retry_count,
          lease_until,
          version,
          created_at,
          updated_at
      FROM supplier_connection
      WHERE status = ?
        AND next_pull_at <= ?
        AND (lease_until IS NULL OR lease_until <= ?)
      ORDER BY next_pull_at ASC, supplier_id ASC
      LIMIT ?
      """;

  private static final String FIND_ALL_SQL = """
      SELECT
          supplier_id,
          supplier_code,
          status,
          pull_interval_seconds,
          next_pull_at,
          last_success_at,
          last_error_at,
          last_cursor,
          retry_count,
          lease_until,
          version,
          created_at,
          updated_at
      FROM supplier_connection
      ORDER BY supplier_id ASC
      """;

  private static final String INSERT_SQL = """
      INSERT INTO supplier_connection (
          supplier_id,
          supplier_code,
          status,
          pull_interval_seconds,
          next_pull_at,
          last_success_at,
          last_error_at,
          last_cursor,
          retry_count,
          lease_until,
          version,
          created_at,
          updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String TRY_ACQUIRE_LEASE_SQL = """
      UPDATE supplier_connection
      SET
          lease_until = ?,
          version = version + 1,
          updated_at = ?
      WHERE supplier_id = ?
        AND status = ?
        AND version = ?
        AND next_pull_at <= ?
        AND (lease_until IS NULL OR lease_until <= ?)
      """;

  private static final String RELEASE_LEASE_SQL = """
      UPDATE supplier_connection
      SET
          lease_until = NULL,
          version = version + 1,
          updated_at = ?
      WHERE supplier_id = ?
        AND version = ?
        AND lease_until IS NOT NULL
      """;

  private static final String MARK_PULL_SUCCESS_SQL = """
      UPDATE supplier_connection
      SET
          last_cursor = ?,
          last_success_at = ?,
          retry_count = 0,
          lease_until = NULL,
          next_pull_at = ?,
          version = version + 1,
          updated_at = ?
      WHERE supplier_id = ?
        AND status = ?
        AND version = ?
      """;

  private static final String MARK_PULL_FAILURE_SQL = """
      UPDATE supplier_connection
      SET
          last_error_at = ?,
          retry_count = ?,
          lease_until = NULL,
          next_pull_at = ?,
          version = version + 1,
          updated_at = ?
      WHERE supplier_id = ?
        AND status = ?
        AND version = ?
      """;

  private final JdbcTemplate jdbcTemplate;

  private final RowMapper<SupplierConnection> supplierConnectionRowMapper = new SupplierConnectionRowMapper();

  @Override
  public Optional<SupplierConnection> findBySupplierId(Long supplierId) {
    List<SupplierConnection> results = jdbcTemplate.query(
        FIND_BY_SUPPLIER_ID_SQL,
        supplierConnectionRowMapper,
        supplierId);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  @Override
  public List<SupplierConnection> findAll() {
    return jdbcTemplate.query(FIND_ALL_SQL, supplierConnectionRowMapper);
  }

  @Override
  public List<SupplierConnection> findSchedulableConnections(LocalDateTime now, int limit) {
    return jdbcTemplate.query(
        FIND_SCHEDULABLE_CONNECTIONS_SQL,
        supplierConnectionRowMapper,
        ConnectionStatus.ACTIVE.name(),
        toTimestamp(now),
        toTimestamp(now),
        limit);
  }

  @Override
  public boolean save(SupplierConnection connection) {
    try {
      int affectedRows = jdbcTemplate.update(
          INSERT_SQL,
          connection.getSupplierId(),
          connection.getSupplierCode(),
          connection.getStatus().name(),
          connection.getPullIntervalSeconds(),
          toTimestamp(connection.getNextPullAt()),
          toTimestamp(connection.getLastSuccessAt()),
          toTimestamp(connection.getLastErrorAt()),
          connection.getLastCursor(),
          connection.getRetryCount(),
          toTimestamp(connection.getLeaseUntil()),
          connection.getVersion(),
          toTimestamp(connection.getCreatedAt()),
          toTimestamp(connection.getUpdatedAt()));
      return affectedRows > 0;
    } catch (DuplicateKeyException ex) {
      return false;
    }
  }

  @Override
  public boolean tryAcquireLease(
      Long supplierId,
      long expectedVersion,
      LocalDateTime now,
      LocalDateTime leaseUntil,
      LocalDateTime updatedAt) {

    int affectedRows = jdbcTemplate.update(
        TRY_ACQUIRE_LEASE_SQL,
        toTimestamp(leaseUntil),
        toTimestamp(updatedAt),
        supplierId,
        ConnectionStatus.ACTIVE.name(),
        expectedVersion,
        toTimestamp(now),
        toTimestamp(now));
    return affectedRows > 0;
  }

  @Override
  public boolean releaseLease(
      Long supplierId,
      long expectedVersion,
      LocalDateTime updatedAt) {

    int affectedRows = jdbcTemplate.update(
        RELEASE_LEASE_SQL,
        toTimestamp(updatedAt),
        supplierId,
        expectedVersion);
    return affectedRows > 0;
  }

  @Override
  public boolean markPullSuccess(
      Long supplierId,
      long expectedVersion,
      String lastCursor,
      LocalDateTime lastSuccessAt,
      LocalDateTime nextPullAt,
      LocalDateTime updatedAt) {

    int affectedRows = jdbcTemplate.update(
        MARK_PULL_SUCCESS_SQL,
        lastCursor,
        toTimestamp(lastSuccessAt),
        toTimestamp(nextPullAt),
        toTimestamp(updatedAt),
        supplierId,
        ConnectionStatus.ACTIVE.name(),
        expectedVersion);
    return affectedRows > 0;
  }

  @Override
  public boolean markPullFailure(
      Long supplierId,
      long expectedVersion,
      LocalDateTime lastErrorAt,
      LocalDateTime nextPullAt,
      int retryCount,
      LocalDateTime updatedAt) {

    int affectedRows = jdbcTemplate.update(
        MARK_PULL_FAILURE_SQL,
        toTimestamp(lastErrorAt),
        retryCount,
        toTimestamp(nextPullAt),
        toTimestamp(updatedAt),
        supplierId,
        ConnectionStatus.ACTIVE.name(),
        expectedVersion);
    return affectedRows > 0;
  }

  private static Timestamp toTimestamp(LocalDateTime value) {
    return value == null ? null : Timestamp.valueOf(value);
  }

  private static final class SupplierConnectionRowMapper
      implements RowMapper<SupplierConnection> {

    @Override
    public SupplierConnection mapRow(ResultSet rs, int rowNum) throws SQLException {
      return SupplierConnection.restore(
          rs.getLong("supplier_id"),
          rs.getString("supplier_code"),
          ConnectionStatus.valueOf(rs.getString("status")),
          rs.getInt("pull_interval_seconds"),
          toLocalDateTime(rs.getTimestamp("next_pull_at")),
          toLocalDateTime(rs.getTimestamp("last_success_at")),
          toLocalDateTime(rs.getTimestamp("last_error_at")),
          rs.getString("last_cursor"),
          rs.getInt("retry_count"),
          toLocalDateTime(rs.getTimestamp("lease_until")),
          rs.getLong("version"),
          toLocalDateTime(rs.getTimestamp("created_at")),
          toLocalDateTime(rs.getTimestamp("updated_at")));
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
      return value == null ? null : value.toLocalDateTime();
    }
  }
}
