package xiaowu.example.supplieretl.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.supplieretl.application.port.PullTaskPublisher;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupplierKafkaProperties.class)
public class SupplierKafkaConfiguration {

  @Bean
  NewTopic supplierPullRequestTopic(SupplierKafkaProperties properties) {
    return TopicBuilder.name(properties.topic().pullRequest())
        .partitions(6)
        .replicas(1)
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(PullTaskPublisher.class)
  PullTaskPublisher pullTaskPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      SupplierKafkaProperties properties) {
    return new KafkaPullTaskPublisher(kafkaTemplate, objectMapper, properties);
  }
}
