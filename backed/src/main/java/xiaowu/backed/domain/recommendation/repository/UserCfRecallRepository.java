package xiaowu.backed.domain.recommendation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import xiaowu.backed.domain.recommendation.entity.UserCfRecall;

public interface UserCfRecallRepository extends JpaRepository<UserCfRecall, Long> {

  List<UserCfRecall> findByUserIdAndModelVersionOrderByRankPositionAsc(Long userId, String modelVersion);

  List<UserCfRecall> findByModelVersionAndUserIdInOrderByUserIdAscRankPositionAsc(
      String modelVersion,
      List<Long> userIds);

  @Query(value = """
      SELECT model_version
      FROM user_cf_recall
      ORDER BY created_at DESC
      LIMIT 1
      """, nativeQuery = true)
  Optional<String> findLatestModelVersion();
}
