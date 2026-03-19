package xiaowu.backed.infrastructure.persistence.recommendation;

import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import xiaowu.backed.domain.recommendation.repository.PreferenceAggregationRepository;

@Repository
@RequiredArgsConstructor
public class JdbcPreferenceAggregationRepository implements PreferenceAggregationRepository {

  private static final String FIND_MAX_DELTA_ID_SQL = """
      SELECT COALESCE(MAX(id), 0)
      FROM user_item_preference_delta
      """;

  private static final String UPSERT_PREFERENCE_SQL = """
      INSERT INTO user_item_preference
          (user_id, item_id, preference_score, interaction_count,
           last_window_start, last_window_end, created_at, updated_at)
      SELECT
          d.user_id,
          d.item_id,
          SUM(d.score_delta) AS preference_score,
          SUM(d.interaction_delta) AS interaction_count,
          MIN(d.window_start) AS last_window_start,
          MAX(d.window_end) AS last_window_end,
          CURRENT_TIMESTAMP(3) AS created_at,
          CURRENT_TIMESTAMP(3) AS updated_at
      FROM user_item_preference_delta d
      WHERE d.id > ? AND d.id <= ?
      GROUP BY d.user_id, d.item_id
      ON DUPLICATE KEY UPDATE
          preference_score = user_item_preference.preference_score + VALUES(preference_score),
          interaction_count = user_item_preference.interaction_count + VALUES(interaction_count),
          last_window_start = LEAST(user_item_preference.last_window_start, VALUES(last_window_start)),
          last_window_end = GREATEST(user_item_preference.last_window_end, VALUES(last_window_end)),
          updated_at = CURRENT_TIMESTAMP(3)
      """;

  private final JdbcTemplate jdbcTemplate;

  @Override
  public long findMaxDeltaId() {
    Long value = jdbcTemplate.queryForObject(FIND_MAX_DELTA_ID_SQL, Long.class);
    return Objects.requireNonNullElse(value, 0L);
  }

  @Override
  public int aggregateDeltaRange(long lowerExclusiveId, long upperInclusiveId) {
    return jdbcTemplate.update(
        UPSERT_PREFERENCE_SQL,
        lowerExclusiveId,
        upperInclusiveId);
  }
}
