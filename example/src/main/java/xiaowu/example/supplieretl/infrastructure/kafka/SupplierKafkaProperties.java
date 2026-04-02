package xiaowu.example.supplieretl.infrastructure.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "supplier.kafka")
public record SupplierKafkaProperties(Topic topic) {

  public SupplierKafkaProperties {
    topic = topic == null ? new Topic("supplier.pull.request") : topic;
  }

  /**
   * Kafka topic names related to supplier pull tasks.
   */
  public record Topic(String pullRequest) {
    public Topic {
      pullRequest = (pullRequest == null || pullRequest.isBlank())
          ? "supplier.pull.request"
          : pullRequest;
    }
  }
}
