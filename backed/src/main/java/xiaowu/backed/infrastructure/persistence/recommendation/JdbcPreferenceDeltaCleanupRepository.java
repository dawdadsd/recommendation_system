package xiaowu.backed.infrastructure.persistence.recommendation;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import xiaowu.backed.domain.recommendation.repository.PreferenceDeltaCleanupRepository;

@Repository
@RequiredArgsConstructor
public class JdbcPreferenceDeltaCleanupRepository implements PreferenceDeltaCleanupRepository {

  private static final String DELETE_EXPIRED_PROCESSED_DELTAS_SQL = """
      DELETE FROM user_item_preference_delta
      WHERE window_end < ?
        AND id <= ?
      ORDER BY id
      LIMIT ?
      """;

  private final JdbcTemplate jdbcTemplate;

  @Override
  public int deleteExpiredProcessedDeltas(
      LocalDateTime cutoffTime,
      long safeUpperBoundId,
      int batchSize) {

    return jdbcTemplate.update(
        DELETE_EXPIRED_PROCESSED_DELTAS_SQL,
        Timestamp.valueOf(cutoffTime),
        safeUpperBoundId,
        batchSize);
  }
}
