package xiaowu.example.payment.seckill.application.port;

import java.time.LocalDateTime;

public interface ReservationCacheGateway {

  /**
   * 尝试预订库存
   *
   * @param command 预订缓存命令
   * @return 预订结果
   */
  ReserveCacheResult tryReserve(ReserveCacheCommand command);

  /**
   * 释放预订库存
   *
   * @param command 释放缓存命令
   * @return 是否释放成功
   */
  boolean release(ReleaseCacheCommand command);

  record ReserveCacheCommand(
      String reservationId,
      Long activityId,
      Long skuId,
      Long userId,
      LocalDateTime expireAt) {
  }

  /**
   * 释放缓存命令
   */
  record ReleaseCacheCommand(
      String reservationId,
      Long activityId,
      Long skuId,
      Long userId,
      String reason) {
  }

  record ReserveCacheResult(
      ReserveStatus status,
      String message) {
    /**
     * 是否预订成功
     *
     * @return 是否预订成功
     */
    public boolean reserved() {
      return this.status == ReserveStatus.RESERVED;
    }
  }

  enum ReserveStatus {
    RESERVED,
    SOLD_OUT,
    DUPLICATE_USER,
    ACTIVITY_CLOSED
  }
}
