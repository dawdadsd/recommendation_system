package xiaowu.example.supplieretl.datasource.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MySQL数据源配置类，包含连接MySQL所需的基本信息
 */
public record MysqlDataSourceConfig(
    String jdbcUrl,
    String username,
    String password,
    String driverClassName,
    String validationQuery) implements DataSourceConfig {

  public MysqlDataSourceConfig {
    jdbcUrl = DataSourceConfigSupport.requireText(jdbcUrl, "jdbcUrl");
    username = DataSourceConfigSupport.requireText(username, "username");
    password = DataSourceConfigSupport.normalizeNullableText(password);
    driverClassName = DataSourceConfigSupport.defaultIfBlank(driverClassName, "com.mysql.cj.jdbc.Driver");
    validationQuery = DataSourceConfigSupport.defaultIfBlank(validationQuery, "SELECT 1");
  }

  @Override
  public void validate() {
    // Validation happens in the canonical constructor.
  }

  @Override
  public Map<String, Object> toMaskedMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("jdbcUrl", jdbcUrl);
    values.put("username", username);
    values.put("password", DataSourceConfigSupport.maskSecret(password));
    values.put("driverClassName", driverClassName);
    values.put("validationQuery", validationQuery);
    return values;
  }
}
