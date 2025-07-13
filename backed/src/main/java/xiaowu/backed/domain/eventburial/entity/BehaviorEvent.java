package xiaowu.backed.domain.eventburial.entity;

import lombok.Getter;
import xiaowu.backed.domain.eventburial.valueobject.BehaviorType;
import xiaowu.backed.domain.eventburial.valueobject.Rating;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 用户行为事件实体
 * 代表用户在系统中的单次行为
 */
@Getter
public class BehaviorEvent {
    private final String eventId;           // 事件唯一标识
    private final Long userId;              // 用户ID
    private final Long itemId;              // 商品ID
    private final BehaviorType behaviorType; // 行为类型
    private final Rating rating;            // 评分（可选）
    private final Instant timestamp;        // 事件时间戳
    private final String sessionId;         // 会话ID
    private final String deviceInfo;        // 设备信息

    // 私有构造函数，强制使用建造者模式
    private BehaviorEvent(Builder builder) {
        this.eventId = builder.eventId;
        this.userId = builder.userId;
        this.itemId = builder.itemId;
        this.behaviorType = builder.behaviorType;
        this.rating = builder.rating;
        this.timestamp = builder.timestamp;
        this.sessionId = builder.sessionId;
        this.deviceInfo = builder.deviceInfo;
    }

    /**
     * 验证事件是否有效
     * 用于Spark流处理中的数据质量检查
     */
    public boolean isValid() {
        return userId != null && userId > 0 &&
                itemId != null && itemId > 0 &&
                behaviorType != null &&
                timestamp != null &&
                !timestamp.isAfter(Instant.now());
    }

    /**
     * 计算事件权重，用于推荐算法
     * 结合行为类型权重和评分
     */
    public double calculateEventWeight() {
        double baseWeight = behaviorType.getWeight();
        if (!rating.isEmpty()) {
            // 评分行为的权重 = 基础权重 * 标准化评分
            return baseWeight * rating.normalize();
        }
        return baseWeight;
    }

    /**
     * 检查事件是否过期（用于实时推荐的时间窗口）
     */
    public boolean isExpired(int validHours) {
        Instant expireTime = timestamp.plusSeconds(validHours * 3600L);
        return Instant.now().isAfter(expireTime);
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BehaviorEvent that = (BehaviorEvent) o;
        return Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }

    /**
     * 建造者模式用于创建行为事件
     * 确保必要字段的完整性
     */
    public static class Builder {
        private final String eventId;
        private Long userId;
        private Long itemId;
        private BehaviorType behaviorType;
        private Rating rating = Rating.empty();
        private Instant timestamp = Instant.now();
        private String sessionId;
        private String deviceInfo;

        public Builder() {
            this.eventId = UUID.randomUUID().toString();
        }

        public Builder userId(Long userId) {
            if (userId == null || userId <= 0) {
                throw new IllegalArgumentException("用户ID必须为正数");
            }
            this.userId = userId;
            return this;
        }

        public Builder itemId(Long itemId) {
            if (itemId == null || itemId <= 0) {
                throw new IllegalArgumentException("商品ID必须为正数");
            }
            this.itemId = itemId;
            return this;
        }

        public Builder behaviorType(BehaviorType behaviorType) {
            this.behaviorType = Objects.requireNonNull(behaviorType, "行为类型不能为空");
            return this;
        }

        public Builder rating(Rating rating) {
            this.rating = rating != null ? rating : Rating.empty();
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = Objects.requireNonNull(timestamp, "时间戳不能为空");
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder deviceInfo(String deviceInfo) {
            this.deviceInfo = deviceInfo;
            return this;
        }

        /**
         * 构建行为事件实例
         * 在构建前进行最终验证
         */
        public BehaviorEvent build() {
            Objects.requireNonNull(userId, "用户ID不能为空");
            Objects.requireNonNull(itemId, "商品ID不能为空");
            Objects.requireNonNull(behaviorType, "行为类型不能为空");
            return new BehaviorEvent(this);
        }
    }
}
