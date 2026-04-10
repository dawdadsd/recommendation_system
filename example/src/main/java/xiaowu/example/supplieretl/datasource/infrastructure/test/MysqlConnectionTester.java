package xiaowu.example.supplieretl.datasource.infrastructure.test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import xiaowu.example.supplieretl.datasource.application.model.ConnectionTestResult;
import xiaowu.example.supplieretl.datasource.application.port.ConnectionTester;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.DataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.model.MysqlDataSourceConfig;

@Component
public class MysqlConnectionTester implements ConnectionTester {

  @Override
  public DataSourceType supports() {
    return DataSourceType.MYSQL;
  }

  @Override
  public ConnectionTestResult test(DataSourceConfig config) {
    MysqlDataSourceConfig mysqlConfig = cast(config, MysqlDataSourceConfig.class, DataSourceType.MYSQL);

    try {
      Class.forName(mysqlConfig.driverClassName());
    } catch (ClassNotFoundException ex) {
      return ConnectionTestResult.failure(
          "MySQL driver not found: " + ex.getMessage(),
          Map.of("driverClassName", mysqlConfig.driverClassName()));
    }

    try (Connection connection = DriverManager.getConnection(
        mysqlConfig.jdbcUrl(),
        mysqlConfig.username(),
        mysqlConfig.password() == null ? "" : mysqlConfig.password());
         Statement statement = connection.createStatement()) {

      statement.setQueryTimeout(5);
      boolean hasResultSet = statement.execute(mysqlConfig.validationQuery());

      Map<String, Object> detail = new HashMap<>();
      detail.put("jdbcUrl", mysqlConfig.jdbcUrl());
      detail.put("databaseProductName", connection.getMetaData().getDatabaseProductName());
      detail.put("databaseProductVersion", connection.getMetaData().getDatabaseProductVersion());
      detail.put("validationQuery", mysqlConfig.validationQuery());

      if (hasResultSet) {
        try (ResultSet resultSet = statement.getResultSet()) {
          if (resultSet.next()) {
            detail.put("validationResult", resultSet.getObject(1));
          }
        }
      } else {
        detail.put("updateCount", statement.getUpdateCount());
      }

      return ConnectionTestResult.success("MySQL connection test passed", detail);
    } catch (Exception ex) {
      return ConnectionTestResult.failure(
          "MySQL connection test failed: " + ex.getMessage(),
          Map.of(
              "jdbcUrl", mysqlConfig.jdbcUrl(),
              "username", mysqlConfig.username(),
              "errorType", ex.getClass().getSimpleName()));
    }
  }

  private static <T extends DataSourceConfig> T cast(
      DataSourceConfig config,
      Class<T> expectedType,
      DataSourceType sourceType) {
    if (!expectedType.isInstance(config)) {
      throw new IllegalArgumentException("Invalid config type for " + sourceType);
    }
    return expectedType.cast(config);
  }
}
