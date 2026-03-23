package xiaowu.example.payment.seckill.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import xiaowu.example.payment.seckill.domain.entity.SeckillReservation;
import xiaowu.example.payment.seckill.domain.entity.SeckillReservation.ReservationStatus;

public interface SeckillReservationRepository {

    Optional<SeckillReservation> findByReservationId(String reservationId);

    Optional<SeckillReservation> findByUser(Long activityId, Long skuId, Long userId);

    /**
     * 保存预订，如果预订ID已存在则返回false
     *
     * @param reservation 预订实体
     * @return 是否保存成功
     */
    boolean saveIfAbsent(SeckillReservation reservation);

    boolean bindPaymentOrder(
            String reservationId,
            ReservationStatus expectedStatus,
            String paymentOrderNo,
            LocalDateTime updatedAt);

    /**
     * 更新预订状态
     *
     * @param reservationId  预订ID
     * @param expectedStatus 预订当前状态
     * @param targetStatus   预订目标状态
     * @param updatedAt      更新时间
     * @return 是否更新成功
     */
    boolean updateStatus(
            String reservationId,
            ReservationStatus expectedStatus,
            ReservationStatus targetStatus,
            LocalDateTime updatedAt);

    boolean markReleased(
            String reservationId,
            ReservationStatus expectedStatus,
            LocalDateTime releasedAt,
            LocalDateTime updatedAt);
}
