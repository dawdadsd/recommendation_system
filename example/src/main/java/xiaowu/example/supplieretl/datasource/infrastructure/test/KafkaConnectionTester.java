package xiaowu.example.supplieretl.datasource.infrastructure.test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.stereotype.Component;

import xiaowu.example.supplieretl.datasource.application.model.ConnectionTestResult;
import xiaowu.example.supplieretl.datasource.application.port.ConnectionTester;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.DataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.model.KafkaDataSourceConfig;

@Component
public class KafkaConnectionTester implements ConnectionTester {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Override
  public DataSourceType supports() {
    return DataSourceType.KAFKA;
  }

  @Override
  public ConnectionTestResult test(DataSourceConfig config) {
    KafkaDataSourceConfig kafkaConfig = cast(config, KafkaDataSourceConfig.class, DataSourceType.KAFKA);

    Properties properties = new Properties();
    properties.put("bootstrap.servers", kafkaConfig.bootstrapServers());
    properties.put("request.timeout.ms", String.valueOf(TIMEOUT.toMillis()));
    properties.put("default.api.timeout.ms", String.valueOf(TIMEOUT.toMillis()));
    if (kafkaConfig.clientId() != null) {
      properties.put("client.id", kafkaConfig.clientId());
    }

    try (AdminClient adminClient = AdminClient.create(properties)) {
      DescribeClusterResult cluster = adminClient.describeCluster();
      long timeoutMs = TIMEOUT.toMillis();
      String clusterId = cluster.clusterId().get(timeoutMs, TimeUnit.MILLISECONDS);
      int nodeCount = cluster.nodes().get(timeoutMs, TimeUnit.MILLISECONDS).size();
      boolean topicExists = adminClient.listTopics().names().get(timeoutMs, TimeUnit.MILLISECONDS)
          .contains(kafkaConfig.topic());

      Map<String, Object> detail = new HashMap<>();
      detail.put("bootstrapServers", kafkaConfig.bootstrapServers());
      detail.put("topic", kafkaConfig.topic());
      detail.put("clusterId", clusterId);
      detail.put("brokerCount", nodeCount);
      detail.put("topicExists", topicExists);

      if (!topicExists) {
        return ConnectionTestResult.failure(
            "Kafka cluster is reachable, but topic does not exist: " + kafkaConfig.topic(),
            detail);
      }

      return ConnectionTestResult.success("Kafka connection test passed", detail);
    } catch (Exception ex) {
      return ConnectionTestResult.failure(
          "Kafka connection test failed: " + ex.getMessage(),
          Map.of(
              "bootstrapServers", kafkaConfig.bootstrapServers(),
              "topic", kafkaConfig.topic(),
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
