package xiaowu.backed.domain.recommendation.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 用户商品偏好增量表。
 * 只记录某个窗口内新增的偏好分和交互次数，供后续批处理汇总为训练矩阵。
 */
@Entity
@Table(name = "user_item_preference_delta", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_item_window", columnNames = { "user_id", "item_id", "window_start",
        "window_end" })
}, indexes = {
    @Index(name = "idx_user_window", columnList = "user_id, window_end"),
    @Index(name = "idx_item_window", columnList = "item_id, window_end")
})
@Getter
@Setter
@NoArgsConstructor
public class UserItemPreferenceDelta {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "item_id", nullable = false)
  private Long itemId;

  @Column(name = "window_start", nullable = false)
  private LocalDateTime windowStart;

  @Column(name = "window_end", nullable = false)
  private LocalDateTime windowEnd;

  @Column(name = "score_delta", nullable = false)
  private Double scoreDelta;

  @Column(name = "interaction_delta", nullable = false)
  private Long interactionDelta;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void onCreate() {
    this.createdAt = LocalDateTime.now();
  }
}
