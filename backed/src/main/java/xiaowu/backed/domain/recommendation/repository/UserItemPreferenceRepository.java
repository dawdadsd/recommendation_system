package xiaowu.backed.domain.recommendation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import xiaowu.backed.domain.recommendation.entity.UserItemPreference;

public interface UserItemPreferenceRepository extends JpaRepository<UserItemPreference, Long> {

  Optional<UserItemPreference> findByUserIdAndItemId(Long userId, Long itemId);

  List<UserItemPreference> findByUserIdOrderByPreferenceScoreDesc(Long userId);

  List<UserItemPreference> findByItemIdOrderByPreferenceScoreDesc(Long itemId);

  long countByPreferenceScoreGreaterThan(double score);
}
