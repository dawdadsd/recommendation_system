package xiaowu.example.payment.seckill.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import xiaowu.example.payment.seckill.application.port.ReservationCacheGateway;
import xiaowu.example.payment.seckill.application.port.ReservationEventPublisher;
import xiaowu.example.payment.seckill.application.service.SeckillReservationApplicationService;
import xiaowu.example.payment.seckill.domain.repository.SeckillReservationRepository;

@Configuration(proxyBeanMethods = false)
public class SeckillApplicationConfiguration {
  @Bean
  @ConditionalOnBean({ ReservationCacheGateway.class, ReservationEventPublisher.class })
  SeckillReservationApplicationService seckillReservationApplicationService(
      SeckillReservationRepository reservationRepository,
      ReservationCacheGateway reservationCacheGateway,
      ReservationEventPublisher reservationEventPublisher) {
    return new SeckillReservationApplicationService(
        reservationRepository,
        reservationCacheGateway,
        reservationEventPublisher);
  }
}
