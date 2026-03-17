package xiaowu.backed.domain.eventburial.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 用户行为窗口聚合记录
 *
 * <p>由 Spark Structured Streaming 的聚合流水线产出，
 * 通过 JDBC upsert 写入。每条记录代表一个 30 秒窗口内
 * 某用户某行为类型的聚合统计。
 *
 * @author xiaowu
 */
@Entity
@Table(name = "user_behavior_aggregation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_behavior_window",
                columnNames = {"userId", "behaviorType", "windowStart", "windowEnd"}),
        indexes = @Index(name = "idx_user_window", columnList = "userId, windowStart"))
@Getter
@Setter
@NoArgsConstructor
public class UserBehaviorAggregation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String behaviorType;

    @Column(nullable = false)
    private LocalDateTime windowStart;

    @Column(nullable = false)
    private LocalDateTime windowEnd;

    @Column(nullable = false)
    private Long eventCount;

    private Double avgRating;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @jakarta.persistence.PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
