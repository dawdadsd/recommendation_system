package xiaowu.backed.domain.recommendation.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import xiaowu.backed.domain.recommendation.entity.UserItemPreferenceDelta;

public interface UserItemPreferenceDeltaRepository extends JpaRepository<UserItemPreferenceDelta, Long> {

  List<UserItemPreferenceDelta> findByUserIdOrderByWindowEndDesc(Long userId);

  List<UserItemPreferenceDelta> findByWindowEndAfterOrderByWindowEndAsc(LocalDateTime windowEnd);

  long countByWindowEndAfter(LocalDateTime windowEnd);
}
