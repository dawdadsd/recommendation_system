package xiaowu.backed.domain.recommendation.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ALS 训练输入：用户-商品偏好矩阵
 * 
 * @author xiaowu
 */
@Entity
@Table(name = "user_item_preference", uniqueConstraints = {
    @UniqueConstraint(name = "uk_user_item", columnNames = { "user_id", "item_id" })
}, indexes = {
    @Index(name = "idx_user_updated", columnList = "user_id, updated_at"),
    @Index(name = "idx_item_updated", columnList = "item_id, updated_at")
})
@Getter
@Setter
@NoArgsConstructor
public class UserItemPreference {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "item_id", nullable = false)
  private Long itemId;

  @Column(name = "preference_score", nullable = false)
  private Double preferenceScore;

  @Column(name = "interaction_count", nullable = false)
  private Long interactionCount = 0L;

  @Column(name = "last_window_start", nullable = false)
  private LocalDateTime lastWindowStart;

  @Column(name = "last_window_end", nullable = false)
  private LocalDateTime lastWindowEnd;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void onCreate() {
    var now = LocalDateTime.now();
    this.createdAt = now;
    this.updatedAt = now;

    if (this.interactionCount == null) {
      this.interactionCount = 0L;
    }
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = LocalDateTime.now();
  }
}
