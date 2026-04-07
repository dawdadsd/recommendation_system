package xiaowu.example.supplieretl.infrastructure.kafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.supplieretl.application.port.PullTaskPublisher;
import xiaowu.example.supplieretl.application.port.RawDataPublisher;
import xiaowu.example.supplieretl.infrastructure.kafka.SupplierKafkaProperties;
import xiaowu.example.supplieretl.infrastructure.kafka.publisher.KafkaPullTaskPublisher;
import xiaowu.example.supplieretl.infrastructure.kafka.publisher.KafkaRawDataPublisher;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupplierKafkaProperties.class)
public class SupplierKafkaConfiguration {

  // ── 拉取任务主题（Worker 消费）─────────────────────────────────────────────────
  @Bean
  NewTopic supplierPullRequestTopic(SupplierKafkaProperties properties) {
    return TopicBuilder.name(properties.topic().pullRequest())
        .partitions(6)
        .replicas(1)
        .build();
  }

  // ── 原始数据主题（Spark ETL 消费）────────────────────────────────────────────
  @Bean
  NewTopic supplierRawDataTopic(SupplierKafkaProperties properties) {
    return TopicBuilder.name(properties.topic().rawData())
        .partitions(12) // 更多分区：支持 Spark 并行消费
        .replicas(1)
        .build();
  }

  // ── 死信主题（人工排查 / 重放）──────────────────────────────────────────────
  @Bean
  NewTopic supplierRawDlqTopic(SupplierKafkaProperties properties) {
    return TopicBuilder.name(properties.topic().rawDlq())
        .partitions(3)
        .replicas(1)
        .build();
  }

  /**
   * 供应商拉取任务发布器，默认实现是基于 Kafka 的 {@link KafkaPullTaskPublisher}。
   * 通过 @ConditionalOnMissingBean 注解允许用户自定义实现替代默认的 Kafka 发布器，
   * 例如直接调用 Worker 的 REST API 等。
   *
   * @param kafkaTemplate Spring Kafka 的模板类，用于发送消息
   * @param objectMapper  Jackson 的对象映射器，用于序列化消息对象为 JSON
   * @param properties    供应商 Kafka 相关配置，包含主题名称等
   * @return PullTaskPublisher 接口的实现实例
   */
  @Bean
  @ConditionalOnMissingBean(PullTaskPublisher.class)
  PullTaskPublisher pullTaskPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      SupplierKafkaProperties properties) {
    return new KafkaPullTaskPublisher(kafkaTemplate, objectMapper, properties);
  }

  @Bean
  @ConditionalOnMissingBean(RawDataPublisher.class)
  RawDataPublisher rawDataPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      SupplierKafkaProperties properties) {
    return new KafkaRawDataPublisher(kafkaTemplate, objectMapper, properties);
  }
}
