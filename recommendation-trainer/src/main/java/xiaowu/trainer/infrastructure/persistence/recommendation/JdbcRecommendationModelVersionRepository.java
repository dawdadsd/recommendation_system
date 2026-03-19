package xiaowu.trainer.infrastructure.persistence.recommendation;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import xiaowu.trainer.domain.recommendation.repository.RecommendationModelVersionRepository;

@Repository
@RequiredArgsConstructor
public class JdbcRecommendationModelVersionRepository implements RecommendationModelVersionRepository {

    private static final String FIND_CURRENT_VERSION_SQL = """
            SELECT current_version
            FROM recommendation_model_version
            WHERE model_name = ?
            """;

    private static final String UPSERT_VERSION_SQL = """
            INSERT INTO recommendation_model_version
                (model_name, current_version, previous_version, status, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                current_version = VALUES(current_version),
                previous_version = VALUES(previous_version),
                status = VALUES(status),
                updated_at = VALUES(updated_at)
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<String> findCurrentVersion(String modelName) {
        List<String> values = jdbcTemplate.query(
                FIND_CURRENT_VERSION_SQL,
                (rs, rowNum) -> rs.getString(1),
                modelName);

        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.get(0));
    }

    @Override
    public void switchVersion(String modelName, String newVersion, String previousVersion, String status) {
        jdbcTemplate.update(
                UPSERT_VERSION_SQL,
                modelName,
                newVersion,
                previousVersion,
                status);
    }
}
