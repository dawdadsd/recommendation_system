package xiaowu.backed.domain.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import xiaowu.backed.domain.user.entity.UserProfile;

/**
 * 用户画像数据访问
 *
 * <p>画像服务的核心查询入口。
 * AI 对话前通过 userId 获取用户静态属性，用于拼装 System Prompt。
 *
 * @author xiaowu
 */
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    Optional<UserProfile> findByUserId(Long userId);

    boolean existsByUserId(Long userId);
}
