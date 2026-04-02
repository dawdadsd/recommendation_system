package xiaowu.example.payment.seckill.infrastructure.kafka;

import java.util.Objects;

import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.payment.seckill.application.port.ReservationEventPublisher;

/**
 * Kafka 实现的预订事件发布器
 * 职责 ： 把应用层的reservation事件,序列化成json字符串,发送到kafka topic
 */

public class KafkaReservationEventPublisher implements ReservationEventPublisher {

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final SeckillKafkaProperties properties;

  public KafkaReservationEventPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      SeckillKafkaProperties properties) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  @Override
  public void publishCreated(ReservationCreatedEvent event) {
    Objects.requireNonNull(event, "event");
    send(properties.topic().reservationCreated(), event.reservationId(), event);
  }

  @Override
  public void publishReleased(ReservationReleasedEvent event) {
    Objects.requireNonNull(event, "event");
    send(properties.topic().reservationRelease(), event.reservationId(), event);
  }

  private void send(String topic, String key, Object event) {
    try {
      String payload = objectMapper.writeValueAsString(event);
      kafkaTemplate.send(topic, key, payload).join();
    } catch (Exception e) {
      throw new RuntimeException("Failed to send event to Kafka", e);
    }
  }
}
