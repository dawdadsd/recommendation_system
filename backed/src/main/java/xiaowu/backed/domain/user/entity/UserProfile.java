package xiaowu.backed.domain.user.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 用户基础画像（静态属性）
 *
 * <p>冷启动基础：新用户无行为数据时，AI 依赖此表进行个性化对话。
 * 包含性别、年龄段、地域和用户自选的偏好标签。
 *
 * @author xiaowu
 */
@Entity
@Table(name = "user_profile", indexes = {
        @Index(name = "idx_gender_age", columnList = "gender, ageRange")
})
@Getter
@Setter
@NoArgsConstructor
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(length = 64)
    private String nickname;

    /** MALE / FEMALE / UNKNOWN */
    @Column(nullable = false, length = 8)
    private String gender = "UNKNOWN";

    /** 18-24 / 25-34 / 35-44 / 45+ */
    @Column(length = 16)
    private String ageRange;

    @Column(length = 64)
    private String region;

    /** 逗号分隔的偏好标签，如 "3C数码,运动户外,美妆" */
    @Column(length = 256)
    private String preferenceTags;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @jakarta.persistence.PrePersist
    void onCreate() {
        var now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
