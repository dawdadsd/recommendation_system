package xiaowu.backed.domain.eventburial.entity;

import lombok.Getter;
import xiaowu.backed.domain.eventburial.valueobject.BehaviorType;
import xiaowu.backed.domain.eventburial.valueobject.Rating;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 用户行为事件实体
 * @author xiaowu
 */
@Getter
public class BehaviorEvent {
    private final String eventId;
    private final Long userId;
    private final Long itemId;
    private final BehaviorType behaviorType;
    private final Rating rating;
    private final Instant timestamp;
    private final String sessionId;
    private final String deviceInfo;

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

    public boolean isValid() {
        return userId != null && userId > 0 &&
                itemId != null && itemId > 0 &&
                behaviorType != null &&
                timestamp != null &&
                !timestamp.isAfter(Instant.now());
    }

    /** 计算事件权重：行为类型基础权重 * 标准化评分 */
    public double calculateEventWeight() {
        double baseWeight = behaviorType.getWeight();
        if (!rating.isEmpty()) {
            return baseWeight * rating.normalize();
        }
        return baseWeight;
    }

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

        public BehaviorEvent build() {
            Objects.requireNonNull(userId, "用户ID不能为空");
            Objects.requireNonNull(itemId, "商品ID不能为空");
            Objects.requireNonNull(behaviorType, "行为类型不能为空");
            return new BehaviorEvent(this);
        }
    }
}
