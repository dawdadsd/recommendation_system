package xiaowu.example.supplieretl.datasource.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import xiaowu.example.supplieretl.datasource.application.support.DataSourceConfigMapper;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnection;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.repository.DataSourceConnectionRepository;

@Repository
public class JdbcDataSourceConnectionRepository implements DataSourceConnectionRepository {

  private static final String FIND_ALL_SQL = """
      SELECT
          id,
          connection_name,
          data_source_type,
          description,
          config_json,
          created_at,
          updated_at
      FROM etl_data_source_connection
      ORDER BY id ASC
      """;

  private static final String FIND_BY_ID_SQL = """
      SELECT
          id,
          connection_name,
          data_source_type,
          description,
          config_json,
          created_at,
          updated_at
      FROM etl_data_source_connection
      WHERE id = ?
      """;

  private static final String FIND_BY_NAME_SQL = """
      SELECT
          id,
          connection_name,
          data_source_type,
          description,
          config_json,
          created_at,
          updated_at
      FROM etl_data_source_connection
      WHERE connection_name = ?
      """;

  private static final String INSERT_SQL = """
      INSERT INTO etl_data_source_connection (
          connection_name,
          data_source_type,
          description,
          config_json,
          created_at,
          updated_at
      ) VALUES (?, ?, ?, ?, ?, ?)
      """;

  private final JdbcTemplate jdbcTemplate;
  private final DataSourceConfigMapper configMapper;
  private final RowMapper<DataSourceConnection> rowMapper = new DataSourceConnectionRowMapper();

  public JdbcDataSourceConnectionRepository(
      JdbcTemplate jdbcTemplate,
      DataSourceConfigMapper configMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.configMapper = configMapper;
  }

  @Override
  public Optional<DataSourceConnection> findById(Long id) {
    List<DataSourceConnection> results = jdbcTemplate.query(FIND_BY_ID_SQL, rowMapper, id);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  @Override
  public Optional<DataSourceConnection> findByConnectionName(String connectionName) {
    List<DataSourceConnection> results = jdbcTemplate.query(FIND_BY_NAME_SQL, rowMapper, connectionName);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  @Override
  public List<DataSourceConnection> findAll() {
    return jdbcTemplate.query(FIND_ALL_SQL, rowMapper);
  }

  @Override
  public DataSourceConnection save(DataSourceConnection connection) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    try {
      jdbcTemplate.update(con -> {
        PreparedStatement statement = con.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
        statement.setString(1, connection.getConnectionName());
        statement.setString(2, connection.getType().name());
        statement.setString(3, connection.getDescription());
        statement.setString(4, configMapper.toJson(connection.getConfig()));
        statement.setTimestamp(5, toTimestamp(connection.getCreatedAt()));
        statement.setTimestamp(6, toTimestamp(connection.getUpdatedAt()));
        return statement;
      }, keyHolder);
    } catch (DuplicateKeyException ex) {
      throw new IllegalStateException(
          "Data source connection already exists: " + connection.getConnectionName(),
          ex);
    }

    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("Failed to obtain generated id for data source connection");
    }

    return DataSourceConnection.restore(
        key.longValue(),
        connection.getConnectionName(),
        connection.getDescription(),
        connection.getType(),
        connection.getConfig(),
        connection.getCreatedAt(),
        connection.getUpdatedAt());
  }

  private static Timestamp toTimestamp(LocalDateTime value) {
    return value == null ? null : Timestamp.valueOf(value);
  }

  private final class DataSourceConnectionRowMapper implements RowMapper<DataSourceConnection> {

    @Override
    public DataSourceConnection mapRow(ResultSet rs, int rowNum) throws SQLException {
      DataSourceType type = DataSourceType.valueOf(rs.getString("data_source_type"));
      return DataSourceConnection.restore(
          rs.getLong("id"),
          rs.getString("connection_name"),
          rs.getString("description"),
          type,
          configMapper.fromJson(type, rs.getString("config_json")),
          toLocalDateTime(rs.getTimestamp("created_at")),
          toLocalDateTime(rs.getTimestamp("updated_at")));
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
      return timestamp == null ? null : timestamp.toLocalDateTime();
    }
  }
}
