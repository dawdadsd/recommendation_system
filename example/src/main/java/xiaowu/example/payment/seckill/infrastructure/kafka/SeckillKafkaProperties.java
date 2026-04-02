package xiaowu.example.payment.seckill.infrastructure.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 秒杀 Kafka 配置属性，绑定到 {@code seckill.kafka} 前缀。
 *
 * <p>
 * 使用 Java record 实现构造器绑定，Spring Boot 3.x 自动检测无需 {@code @ConstructorBinding}。
 * 内层 {@code Topic} record 同理，relaxed binding 会将 YAML 中的
 * {@code reservation-created} 映射到字段 {@code reservationCreated}。
 */
@ConfigurationProperties(prefix = "seckill.kafka")
public record SeckillKafkaProperties(Topic topic) {

  public record Topic(String reservationCreated, String reservationRelease) {
  }
}
