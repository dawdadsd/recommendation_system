package xiaowu.backed.application.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户行为事件 DTO，用于 Kafka 消息 JSON 序列化/反序列化
 * 
 * @author xiaowu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorEventDTO {

    private String eventId;
    private Long userId;
    private Long itemId;
    /** 行为类型: VIEW / CLICK / ADD_TO_CART / PURCHASE / RATE */
    private String behaviorType;
    /** 评分 1.0-5.0，仅 RATE 类型时有效 */
    private Double rating;
    private String sessionId;
    private String deviceInfo;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;
}
