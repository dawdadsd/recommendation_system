package xiaowu.example.supplieretl.datasource.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kafka数据源配置类，包含连接Kafka所需的基本信息
 *
 * @param bootstrapServers Kafka集群的地址列表，格式为host:port，多个地址用逗号分隔
 * @param topic            Kafka主题名称，指定要连接的主题
 * @param clientId         可选的客户端ID，用于标识连接的客户端，便于Kafka集群进行连接管理和监控
 */
public record KafkaDataSourceConfig(
    String bootstrapServers,
    String topic,
    String clientId) implements DataSourceConfig {

  public KafkaDataSourceConfig {
    bootstrapServers = DataSourceConfigSupport.requireText(bootstrapServers, "bootstrapServers");
    topic = DataSourceConfigSupport.requireText(topic, "topic");
    clientId = DataSourceConfigSupport.normalizeNullableText(clientId);
  }

  @Override
  public void validate() {
    // Validation happens in the canonical constructor.
  }

  @Override
  public Map<String, Object> toMaskedMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("bootstrapServers", bootstrapServers);
    values.put("topic", topic);
    if (clientId != null) {
      values.put("clientId", clientId);
    }
    return values;
  }
}
