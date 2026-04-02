package xiaowu.example.payment.seckill.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.payment.seckill.application.port.ReservationEventPublisher;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SeckillKafkaProperties.class)
public class SeckillKafkaConfiguration {
  @Bean
  NewTopic reservationCreatedTopic(SeckillKafkaProperties properties) {
    return TopicBuilder.name(properties.topic().reservationCreated())
        .partitions(6)
        .replicas(1)
        .build();
  }

  @Bean
  NewTopic reservationReleaseTopic(SeckillKafkaProperties properties) {
    return TopicBuilder.name(properties.topic().reservationRelease())
        .partitions(6)
        .replicas(1)
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(ReservationEventPublisher.class)
  ReservationEventPublisher reservationEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      SeckillKafkaProperties properties) {
    return new KafkaReservationEventPublisher(kafkaTemplate, objectMapper, properties);
  }
}
