package xiaowu.example.supplieretl.datasource.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnectionTestLog;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.repository.DataSourceConnectionTestLogRepository;

@Repository
public class JdbcDataSourceConnectionTestLogRepository implements DataSourceConnectionTestLogRepository {

  private static final String INSERT_SQL = """
      INSERT INTO etl_connection_test_log (
          connection_id,
          data_source_type,
          success,
          message,
          detail_json,
          tested_at
      ) VALUES (?, ?, ?, ?, ?, ?)
      """;

  private static final String FIND_RECENT_SQL = """
      SELECT
          id,
          connection_id,
          data_source_type,
          success,
          message,
          detail_json,
          tested_at
      FROM etl_connection_test_log
      WHERE connection_id = ?
      ORDER BY tested_at DESC, id DESC
      LIMIT ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final RowMapper<DataSourceConnectionTestLog> rowMapper = new TestLogRowMapper();

  public JdbcDataSourceConnectionTestLogRepository(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public DataSourceConnectionTestLog save(DataSourceConnectionTestLog log) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(con -> {
      PreparedStatement statement = con.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
      statement.setLong(1, log.getConnectionId());
      statement.setString(2, log.getDataSourceType().name());
      statement.setBoolean(3, log.isSuccess());
      statement.setString(4, log.getMessage());
      statement.setString(5, toJson(log.getDetail()));
      statement.setTimestamp(6, Timestamp.valueOf(log.getTestedAt()));
      return statement;
    }, keyHolder);

    Number key = keyHolder.getKey();
    return DataSourceConnectionTestLog.restore(
        key == null ? null : key.longValue(),
        log.getConnectionId(),
        log.getDataSourceType(),
        log.isSuccess(),
        log.getMessage(),
        log.getDetail(),
        log.getTestedAt());
  }

  @Override
  public List<DataSourceConnectionTestLog> findRecentByConnectionId(Long connectionId, int limit) {
    return jdbcTemplate.query(FIND_RECENT_SQL, rowMapper, connectionId, limit);
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize connection test detail", ex);
    }
  }

  private final class TestLogRowMapper implements RowMapper<DataSourceConnectionTestLog> {

    @Override
    public DataSourceConnectionTestLog mapRow(ResultSet rs, int rowNum) throws SQLException {
      return DataSourceConnectionTestLog.restore(
          rs.getLong("id"),
          rs.getLong("connection_id"),
          DataSourceType.valueOf(rs.getString("data_source_type")),
          rs.getBoolean("success"),
          rs.getString("message"),
          readDetail(rs.getString("detail_json")),
          rs.getTimestamp("tested_at").toLocalDateTime());
    }

    private java.util.Map<String, Object> readDetail(String json) {
      if (json == null || json.isBlank()) {
        return java.util.Map.of();
      }
      try {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
      } catch (JsonProcessingException ex) {
        throw new IllegalStateException("Failed to deserialize connection test detail", ex);
      }
    }
  }
}
