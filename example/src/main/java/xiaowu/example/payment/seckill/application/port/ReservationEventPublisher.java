package xiaowu.example.payment.seckill.application.port;

import java.time.LocalDateTime;

public interface ReservationEventPublisher {
    /**
     * 发布预订创建事件
     *
     * @param event 预订创建事件
     */
    void publishCreated(ReservationCreatedEvent event);

    /**
     * 发布预订释放事件
     *
     * @param event 预订释放事件
     */
    void publishReleased(ReservationReleasedEvent event);

    /**
     * 预订创建事件
     */
    record ReservationCreatedEvent(
            String reservationId,
            Long activityId,
            Long skuId,
            Long userId,
            LocalDateTime expireAt,
            LocalDateTime occurredAt) {
    }

    /**
     * 预订释放事件
     */

    record ReservationReleasedEvent(
            String reservationId,
            Long activityId,
            Long skuId,
            Long userId,
            String reason,
            LocalDateTime occurredAt) {
    }
}
