package xiaowu.example.supplieretl.infrastructure.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "supplier.kafka")
public record SupplierKafkaProperties(Topic topic) {

  public SupplierKafkaProperties {
    topic = topic == null ? new Topic(null, null, null) : topic;
  }

  /**
   * Kafka topic names related to supplier pull tasks.
   *
   * <ul>
   * <li>{@code pullRequest} — 调度任务主题，Worker 消费后执行 ERP 拉取</li>
   * <li>{@code rawData} — 原始数据主题，Spark ETL 消费做清洗聚合</li>
   * <li>{@code rawDlq} — 死信主题，解析失败 / 不可重试的消息落这里</li>
   * </ul>
   */
  public record Topic(String pullRequest, String rawData, String rawDlq) {
    public Topic {
      pullRequest = (pullRequest == null || pullRequest.isBlank())
          ? "supplier.pull.request"
          : pullRequest;
      rawData = (rawData == null || rawData.isBlank())
          ? "supplier.raw.data"
          : rawData;
      rawDlq = (rawDlq == null || rawDlq.isBlank())
          ? "supplier.raw.dlq"
          : rawDlq;
    }
  }
}
