package xiaowu.backed.infrastructure.persistence.recommendation;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import xiaowu.backed.domain.recommendation.repository.RecommendationJobCheckpointRepository;

@Repository
@RequiredArgsConstructor
public class JdbcRecommendationJobCheckpointRepository implements RecommendationJobCheckpointRepository {

  private static final String FIND_CHECKPOINT_SQL = """
      SELECT last_processed_id
      FROM recommendation_job_checkpoint
      WHERE job_name = ?
      """;

  private static final String UPSERT_CHECKPOINT_SQL = """
      INSERT INTO recommendation_job_checkpoint
          (job_name, last_processed_id, updated_at)
      VALUES (?, ?, CURRENT_TIMESTAMP(3))
      ON DUPLICATE KEY UPDATE
          last_processed_id = VALUES(last_processed_id),
          updated_at = VALUES(updated_at)
      """;

  private final JdbcTemplate jdbcTemplate;

  @Override
  public long findLastProcessedId(String jobName) {
    List<Long> values = jdbcTemplate.query(
        FIND_CHECKPOINT_SQL,
        (rs, rowNum) -> rs.getLong(1),
        jobName);

    return values.isEmpty() ? 0L : values.get(0);
  }

  @Override
  public void saveLastProcessedId(String jobName, long lastProcessedId) {
    jdbcTemplate.update(
        UPSERT_CHECKPOINT_SQL,
        jobName,
        lastProcessedId);
  }
}
