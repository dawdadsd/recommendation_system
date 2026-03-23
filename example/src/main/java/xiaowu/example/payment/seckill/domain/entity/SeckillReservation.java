package xiaowu.example.payment.seckill.domain.entity;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 秒杀预订实体
 */
public class SeckillReservation {

  private final String reservationId;
  private final Long activityId;
  private final Long skuId;
  private final Long userId;
  private final String reservationToken;
  private ReservationStatus status;
  private String paymentOrderNo;
  private final LocalDateTime expireAt;
  private LocalDateTime releasedAt;
  private final LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  private SeckillReservation(
      String reservationId,
      Long activityId,
      Long skuId,
      Long userId,
      String reservationToken,
      ReservationStatus status,
      String paymentOrderNo,
      LocalDateTime expireAt,
      LocalDateTime releasedAt,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    this.reservationId = requireText(reservationId, "reservationId must not be blank");
    this.activityId = requirePositive(activityId, "activityId must be positive");
    this.skuId = requirePositive(skuId, "skuId must be positive");
    this.userId = requirePositive(userId, "userId must be positive");
    this.reservationToken = requireText(reservationToken, "reservationToken must not be blank");
    this.status = Objects.requireNonNull(status, "status must not be null");
    this.paymentOrderNo = paymentOrderNo;
    this.expireAt = Objects.requireNonNull(expireAt, "expireAt must not be null");
    this.releasedAt = releasedAt;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }

  /**
   * 创建预订
   *
   * @param reservationId    预订ID
   * @param activityId       秒杀活动ID
   * @param skuId            商品ID
   * @param userId           用户ID
   * @param reservationToken 预订令牌
   * @param expireAt         过期时间
   * @return 预订实体
   */
  public static SeckillReservation reserve(
      String reservationId,
      Long activityId,
      Long skuId,
      Long userId,
      String reservationToken,
      LocalDateTime expireAt) {

    LocalDateTime now = LocalDateTime.now();
    return new SeckillReservation(
        reservationId,
        activityId,
        skuId,
        userId,
        reservationToken,
        ReservationStatus.RESERVED,
        null,
        expireAt,
        null,
        now,
        now);
  }

  /**
   * 从持久化状态恢复预订实体
   *
   * @param reservationId    预订ID
   * @param activityId       秒杀活动ID
   * @param skuId            商品ID
   * @param userId           用户ID
   * @param reservationToken 预订令牌
   * @param status           预订状态
   * @param paymentOrderNo   支付订单号
   * @param expireAt         过期时间
   * @param releasedAt       释放时间
   * @param createdAt        创建时间
   * @param updatedAt        更新时间
   * @return
   */
  public static SeckillReservation restore(
      String reservationId,
      Long activityId,
      Long skuId,
      Long userId,
      String reservationToken,
      ReservationStatus status,
      String paymentOrderNo,
      LocalDateTime expireAt,
      LocalDateTime releasedAt,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    SeckillReservation reservation = new SeckillReservation(
        reservationId,
        activityId,
        skuId,
        userId,
        reservationToken,
        status,
        paymentOrderNo,
        expireAt,
        releasedAt,
        createdAt,
        updatedAt);
    validateRestoredState(reservation);
    return reservation;
  }

  /**
   * 标记订单已创建
   *
   * @param paymentOrderNo 支付订单号
   */
  public void markOrderCreated(String paymentOrderNo) {
    assertCurrentStatus(ReservationStatus.RESERVED, "Only RESERVED can move to ORDER_CREATED");
    this.paymentOrderNo = requireText(paymentOrderNo, "paymentOrderNo must not be blank");
    this.status = ReservationStatus.ORDER_CREATED;
    this.updatedAt = LocalDateTime.now();
  }

  /**
   * 标记订单已支付
   */
  public void markPaid() {
    assertCurrentStatus(ReservationStatus.ORDER_CREATED, "Only ORDER_CREATED can move to PAID");
    this.status = ReservationStatus.PAID;
    this.updatedAt = LocalDateTime.now();
  }

  /**
   * 标记预订已取消
   */
  public void markCancelled() {
    if (this.status != ReservationStatus.RESERVED && this.status != ReservationStatus.ORDER_CREATED) {
      throw new IllegalStateException(
          "Only RESERVED or ORDER_CREATED can be cancelled, current status: " + this.status);
    }
    this.status = ReservationStatus.CANCELLED;
    this.updatedAt = LocalDateTime.now();
  }

  /**
   * 标记预订已过期
   */
  public void markExpired() {
    if (this.status != ReservationStatus.RESERVED && this.status != ReservationStatus.ORDER_CREATED) {
      throw new IllegalStateException("Only RESERVED or ORDER_CREATED can expire, current status: " + this.status);
    }
    this.status = ReservationStatus.EXPIRED;
    this.updatedAt = LocalDateTime.now();
  }

  /**
   * 标记预订已释放
   */
  public void markReleased() {
    if (!canRelease()) {
      throw new IllegalStateException("Current status cannot be released: " + this.status);
    }
    this.status = ReservationStatus.RELEASED;
    this.releasedAt = LocalDateTime.now();
    this.updatedAt = this.releasedAt;
  }

  /**
   * 判断当前预订是否可以释放
   *
   * @return 是否可以释放
   */
  public boolean canRelease() {
    return this.status == ReservationStatus.RESERVED
        || this.status == ReservationStatus.ORDER_CREATED
        || this.status == ReservationStatus.CANCELLED
        || this.status == ReservationStatus.EXPIRED;
  }

  /**
   * 判断当前预订是否处于终态
   *
   * @return 是否处于终态
   */
  public boolean isTerminal() {
    return this.status == ReservationStatus.PAID || this.status == ReservationStatus.RELEASED;
  }

  /**
   * 验证从持久化状态恢复的预订实体是否合法
   *
   * @param reservation 预订实体
   */
  private static void validateRestoredState(SeckillReservation reservation) {
    if (reservation.status == ReservationStatus.ORDER_CREATED
        && (reservation.paymentOrderNo == null || reservation.paymentOrderNo.isBlank())) {
      throw new IllegalStateException("ORDER_CREATED requires paymentOrderNo");
    }
    if (reservation.status == ReservationStatus.PAID
        && (reservation.paymentOrderNo == null || reservation.paymentOrderNo.isBlank())) {
      throw new IllegalStateException("PAID requires paymentOrderNo");
    }
    if (reservation.status == ReservationStatus.RELEASED && reservation.releasedAt == null) {
      throw new IllegalStateException("RELEASED requires releasedAt");
    }
  }

  /**
   * 验证当前状态是否符合预期
   *
   * @param expected 预期状态
   * @param message  错误消息
   */

  private void assertCurrentStatus(ReservationStatus expected, String message) {
    if (this.status != expected) {
      throw new IllegalStateException(message + ", current status: " + this.status);
    }
  }

  /**
   * 验证文本参数不为null或空白
   *
   * @param value   待验证的文本参数
   * @param message 错误消息
   * @return 原始文本参数，如果验证通过
   */
  private static String requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  private static Long requirePositive(Long value, String message) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  public String getReservationId() {
    return reservationId;
  }

  public Long getActivityId() {
    return activityId;
  }

  public Long getSkuId() {
    return skuId;
  }

  public Long getUserId() {
    return userId;
  }

  public String getReservationToken() {
    return reservationToken;
  }

  public ReservationStatus getStatus() {
    return status;
  }

  public String getPaymentOrderNo() {
    return paymentOrderNo;
  }

  public LocalDateTime getExpireAt() {
    return expireAt;
  }

  public LocalDateTime getReleasedAt() {
    return releasedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public enum ReservationStatus {
    RESERVED,
    ORDER_CREATED,
    PAID,
    CANCELLED,
    EXPIRED,
    RELEASED
  }
}
