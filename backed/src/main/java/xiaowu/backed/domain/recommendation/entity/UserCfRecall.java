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
 * ALS 协同过滤召回结果
 * 
 * @author xiaowu
 */
@Entity
@Table(name = "user_cf_recall", uniqueConstraints = {
    @UniqueConstraint(name = "uk_model_user_item", columnNames = { "model_version", "user_id", "item_id" })
}, indexes = {
    @Index(name = "idx_user_model_rank", columnList = "user_id, model_version, rank_position"),
    @Index(name = "idx_model_version", columnList = "model_version")
})
@Getter
@Setter
@NoArgsConstructor
public class UserCfRecall {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "model_version", nullable = false, length = 64)
  private String modelVersion;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "item_id", nullable = false)
  private Long itemId;

  @Column(name = "rank_position", nullable = false)
  private Integer rankPosition;

  @Column(name = "score", nullable = false)
  private Double score;

  @Column(name = "source", nullable = false, length = 32)
  private String source = "ALS";

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void onCreate() {
    var now = LocalDateTime.now();
    this.createdAt = now;
    this.updatedAt = now;

    if (this.source == null || this.source.isBlank()) {
      this.source = "ALS";
    }
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = LocalDateTime.now();
  }
}
