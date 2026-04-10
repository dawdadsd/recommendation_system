package xiaowu.example.supplieretl.datasource.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis数据源配置类，包含连接Redis所需的基本信息
 */
public record RedisDataSourceConfig(
    String host,
    Integer port,
    Integer database,
    String password,
    String keyPattern) implements DataSourceConfig {

  public RedisDataSourceConfig {
    host = DataSourceConfigSupport.requireText(host, "host");
    port = DataSourceConfigSupport.requirePositive(port, 6379, "port");
    database = DataSourceConfigSupport.requireNonNegative(database, 0, "database");
    password = DataSourceConfigSupport.normalizeNullableText(password);
    keyPattern = DataSourceConfigSupport.defaultIfBlank(keyPattern, "*");
  }

  @Override
  public void validate() {
    // Validation happens in the canonical constructor.
  }

  @Override
  public Map<String, Object> toMaskedMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("host", host);
    values.put("port", port);
    values.put("database", database);
    values.put("password", DataSourceConfigSupport.maskSecret(password));
    values.put("keyPattern", keyPattern);
    return values;
  }
}
