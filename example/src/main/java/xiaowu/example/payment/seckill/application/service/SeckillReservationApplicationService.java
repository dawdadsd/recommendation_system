package xiaowu.example.payment.seckill.application.service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import xiaowu.example.payment.seckill.application.port.ReservationCacheGateway;
import xiaowu.example.payment.seckill.application.port.ReservationCacheGateway.ReleaseCacheCommand;
import xiaowu.example.payment.seckill.application.port.ReservationCacheGateway.ReserveCacheCommand;
import xiaowu.example.payment.seckill.application.port.ReservationEventPublisher;
import xiaowu.example.payment.seckill.application.port.ReservationEventPublisher.ReservationCreatedEvent;
import xiaowu.example.payment.seckill.application.port.ReservationEventPublisher.ReservationReleasedEvent;
import xiaowu.example.payment.seckill.domain.entity.SeckillReservation;
import xiaowu.example.payment.seckill.domain.entity.SeckillReservation.ReservationStatus;
import xiaowu.example.payment.seckill.domain.repository.SeckillReservationRepository;

/**
 * 这是一个教学框架，还不是 Spring bean。
 *
 * <p>
 * 将其保留在组件扫描之外，直到 Redis/Kafka 适配器可用
 * 已实施。
 */

public class SeckillReservationApplicationService {

  private final SeckillReservationRepository reservationRepository;
  private final ReservationCacheGateway reservationCacheGateway;
  private final ReservationEventPublisher reservationEventPublisher;

  public SeckillReservationApplicationService(
      SeckillReservationRepository reservationRepository,
      ReservationCacheGateway reservationCacheGateway,
      ReservationEventPublisher reservationEventPublisher) {
    this.reservationRepository = Objects.requireNonNull(reservationRepository, "reservationRepository");
    this.reservationCacheGateway = Objects.requireNonNull(reservationCacheGateway, "reservationCacheGateway");
    this.reservationEventPublisher = Objects.requireNonNull(reservationEventPublisher, "reservationEventPublisher");
  }

  /**
   * 处理预订请求
   *
   * @param command 预订命令
   * @return 预订结果
   */
  public ReserveResult reserve(ReserveCommand command) {
    LocalDateTime expireAt = LocalDateTime.now().plusSeconds(command.reserveSeconds());
    String reservationId = command.reservationId() == null || command.reservationId().isBlank()
        ? "RSV_" + UUID.randomUUID()
        : command.reservationId();

    var cacheResult = reservationCacheGateway.tryReserve(new ReserveCacheCommand(
        reservationId,
        command.activityId(),
        command.skuId(),
        command.userId(),
        expireAt));

    if (!cacheResult.reserved()) {
      return new ReserveResult(null, false, cacheResult.message());
    }

    SeckillReservation reservation = SeckillReservation.reserve(
        reservationId,
        command.activityId(),
        command.skuId(),
        command.userId(),
        command.reservationToken(),
        expireAt);

    boolean saved = reservationRepository.saveIfAbsent(reservation);
    if (!saved) {
      reservationCacheGateway.release(new ReleaseCacheCommand(
          reservationId,
          command.activityId(),
          command.skuId(),
          command.userId(),
          "DB_SAVE_CONFLICT"));
      return new ReserveResult(null, false, "Reservation already exists in DB");
    }
    try {
      reservationEventPublisher.publishCreated(new ReservationCreatedEvent(
          reservation.getReservationId(),
          reservation.getActivityId(),
          reservation.getSkuId(),
          reservation.getUserId(),
          reservation.getExpireAt(),
          reservation.getCreatedAt()));
    } catch (RuntimeException ex) {
      reservationRepository.updateStatus(
          reservation.getReservationId(),
          ReservationStatus.RESERVED,
          ReservationStatus.CANCELLED,
          LocalDateTime.now());
      reservationCacheGateway.release(new ReleaseCacheCommand(
          reservation.getReservationId(),
          reservation.getActivityId(),
          reservation.getSkuId(),
          reservation.getUserId(),
          "EVENT_PUBLISH_FAILED"));
      throw new IllegalStateException("Failed to publish reservation.created event", ex);
    }
    /**
     * 预订成功，订单创建应继续异步进行
     */
    return new ReserveResult(
        reservation,
        true,
        "Reservation accepted, order creation should continue asynchronously");
  }

  public ReleaseResult requestRelease(ReleaseCommand command) {
    SeckillReservation reservation = reservationRepository.findByReservationId(command.reservationId())
        .orElseThrow(() -> new IllegalStateException("Reservation not found: " + command.reservationId()));

    if (reservation.getStatus() == ReservationStatus.PAID) {
      throw new IllegalStateException("Paid reservation cannot be released");
    }

    if (reservation.getStatus() == ReservationStatus.RELEASED) {
      return new ReleaseResult(reservation.getReservationId(), false, "Reservation already released");
    }

    reservationEventPublisher.publishReleased(new ReservationReleasedEvent(
        reservation.getReservationId(),
        reservation.getActivityId(),
        reservation.getSkuId(),
        reservation.getUserId(),
        command.reason(),
        LocalDateTime.now()));

    return new ReleaseResult(reservation.getReservationId(), true, "Release event published");
  }

  /**
   * 预订命令
   *
   * @param reservationId    预订ID，允许外部指定以实现幂等，如果不提供则自动生成
   * @param activityId       秒杀活动ID
   * @param skuId            商品SKU ID
   * @param userId           用户ID
   * @param reservationToken 预订令牌，由调用方生成并验证，防止伪造请求
   * @param reserveSeconds   预订有效期，单位秒，过期后库存将自动释放
   */
  public record ReserveCommand(
      String reservationId,
      Long activityId,
      Long skuId,
      Long userId,
      String reservationToken,
      long reserveSeconds) {
  }

  public record ReleaseCommand(
      String reservationId,
      String reason) {
  }

  public record ReserveResult(
      SeckillReservation reservation,
      boolean reserved,
      String message) {
  }

  public record ReleaseResult(
      String reservationId,
      boolean accepted,
      String message) {
  }
}
