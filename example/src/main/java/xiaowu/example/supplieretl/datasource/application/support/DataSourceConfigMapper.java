package xiaowu.example.supplieretl.datasource.application.support;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.DataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.model.ExcelDataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.model.KafkaDataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.model.MysqlDataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.model.RedisDataSourceConfig;

/**
 * 数据源配置映射器，负责将JSON格式的配置转换为对应的数据源配置对象，以及将数据源配置对象序列化为JSON字符串
 */
@Component
public class DataSourceConfigMapper {

  private final ObjectMapper objectMapper;

  public DataSourceConfigMapper(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  /**
   * 将JSON格式的配置转换为对应数据源配置对象
   *
   * @param type       数据源类型，决定了要转换成哪种具体的数据源配置类
   * @param configNode 包含数据源配置的JSON节点，应该包含与指定数据源类型对应的字段和结构
   * @return 转换后的数据源配置对象
   */
  public DataSourceConfig fromJsonNode(DataSourceType type, JsonNode configNode) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(configNode, "configNode must not be null");
    try {
      DataSourceConfig config = objectMapper.treeToValue(configNode, resolveClass(type));
      config.validate();
      return config;
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to parse config for type " + type, ex);
    }
  }

  public DataSourceConfig fromJson(DataSourceType type, String json) {
    Objects.requireNonNull(type, "type must not be null");
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("config json must not be blank");
    }
    try {
      DataSourceConfig config = objectMapper.readValue(json, resolveClass(type));
      config.validate();
      return config;
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to deserialize config for type " + type, ex);
    }
  }

  public String toJson(DataSourceConfig config) {
    Objects.requireNonNull(config, "config must not be null");
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Failed to serialize data source config", ex);
    }
  }

  private static Class<? extends DataSourceConfig> resolveClass(DataSourceType type) {
    return switch (type) {
      case KAFKA -> KafkaDataSourceConfig.class;
      case MYSQL -> MysqlDataSourceConfig.class;
      case REDIS -> RedisDataSourceConfig.class;
      case EXCEL -> ExcelDataSourceConfig.class;
    };
  }
}
