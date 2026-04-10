package xiaowu.example.supplieretl.datasource.domain.model;

import java.util.Map;

/**
 * 数据源配置接口，定义了数据源配置的基本行为和属性
 *
 * @author xiaowu
 */
public sealed interface DataSourceConfig
    permits KafkaDataSourceConfig, MysqlDataSourceConfig, RedisDataSourceConfig, ExcelDataSourceConfig {
  /**
   * 验证配置的合法性，确保所有必需的字段都已正确设置，并且值符合预期的格式和范围
   */
  void validate();

  /**
   * 将配置转换为一个包含敏感信息已被掩码处理的Map，方便在日志或调试输出中使用，同时保护敏感数据不被泄露
   *
   * @return 一个Map，包含配置的字段和值，其中敏感信息已被掩码处理
   */
  Map<String, Object> toMaskedMap();
}
