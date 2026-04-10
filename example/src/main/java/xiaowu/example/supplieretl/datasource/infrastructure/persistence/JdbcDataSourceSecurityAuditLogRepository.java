package xiaowu.example.supplieretl.datasource.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceSecurityAuditLog;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.repository.DataSourceSecurityAuditLogRepository;
import xiaowu.example.supplieretl.datasource.security.AuditActionType;
import xiaowu.example.supplieretl.datasource.security.SecurityAuditStatus;

@Repository
public class JdbcDataSourceSecurityAuditLogRepository implements DataSourceSecurityAuditLogRepository {

  private static final String INSERT_SQL = """
      INSERT INTO etl_connection_security_audit (
          action,
          connection_id,
          data_source_type,
          actor_id,
          client_ip,
          target_summary,
          resolved_addresses,
          success,
          status,
          message,
          detail_json,
          created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String FIND_RECENT_SQL = """
      SELECT
          id,
          action,
          connection_id,
          data_source_type,
          actor_id,
          client_ip,
          target_summary,
          resolved_addresses,
          success,
          status,
          message,
          detail_json,
          created_at
      FROM etl_connection_security_audit
      ORDER BY created_at DESC, id DESC
      LIMIT ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final RowMapper<DataSourceSecurityAuditLog> rowMapper = new AuditLogRowMapper();

  public JdbcDataSourceSecurityAuditLogRepository(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public DataSourceSecurityAuditLog save(DataSourceSecurityAuditLog log) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(con -> {
      PreparedStatement statement = con.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
      statement.setString(1, log.getAction().name());
      if (log.getConnectionId() == null) {
        statement.setObject(2, null);
      } else {
        statement.setLong(2, log.getConnectionId());
      }
      statement.setString(3, log.getDataSourceType().name());
      statement.setString(4, log.getActorId());
      statement.setString(5, log.getClientIp());
      statement.setString(6, log.getTargetSummary());
      statement.setString(7, log.getResolvedAddresses());
      statement.setBoolean(8, log.isSuccess());
      statement.setString(9, log.getStatus().name());
      statement.setString(10, log.getMessage());
      statement.setString(11, toJson(log.getDetail()));
      statement.setTimestamp(12, Timestamp.valueOf(log.getCreatedAt()));
      return statement;
    }, keyHolder);

    Number key = keyHolder.getKey();
    return DataSourceSecurityAuditLog.restore(
        key == null ? null : key.longValue(),
        log.getAction(),
        log.getConnectionId(),
        log.getDataSourceType(),
        log.getActorId(),
        log.getClientIp(),
        log.getTargetSummary(),
        log.getResolvedAddresses(),
        log.isSuccess(),
        log.getStatus(),
        log.getMessage(),
        log.getDetail(),
        log.getCreatedAt());
  }

  @Override
  public List<DataSourceSecurityAuditLog> findRecent(int limit) {
    return jdbcTemplate.query(FIND_RECENT_SQL, rowMapper, limit);
  }

  private String toJson(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize security audit detail", ex);
    }
  }

  private final class AuditLogRowMapper implements RowMapper<DataSourceSecurityAuditLog> {

    @Override
    public DataSourceSecurityAuditLog mapRow(ResultSet rs, int rowNum) throws SQLException {
      Long connectionId = rs.getObject("connection_id") == null ? null : rs.getLong("connection_id");
      return DataSourceSecurityAuditLog.restore(
          rs.getLong("id"),
          AuditActionType.valueOf(rs.getString("action")),
          connectionId,
          DataSourceType.valueOf(rs.getString("data_source_type")),
          rs.getString("actor_id"),
          rs.getString("client_ip"),
          rs.getString("target_summary"),
          rs.getString("resolved_addresses"),
          rs.getBoolean("success"),
          SecurityAuditStatus.valueOf(rs.getString("status")),
          rs.getString("message"),
          readDetail(rs.getString("detail_json")),
          rs.getTimestamp("created_at").toLocalDateTime());
    }

    private Map<String, Object> readDetail(String json) {
      if (json == null || json.isBlank()) {
        return Map.of();
      }
      try {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
      } catch (JsonProcessingException ex) {
        throw new IllegalStateException("Failed to deserialize security audit detail", ex);
      }
    }
  }
}
