package xiaowu.backed.infrastructure.persistence.recommendation;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import xiaowu.backed.domain.recommendation.repository.ModelVersionReadRepository;

@Repository
@RequiredArgsConstructor
public class JdbcModelVersionReadRepository implements ModelVersionReadRepository {

  private static final String FIND_ACTIVE_VERSION_SQL = """
      SELECT current_version
      FROM recommendation_model_version
      WHERE model_name = ? AND status = 'ACTIVE'
      """;

  private final JdbcTemplate jdbcTemplate;

  @Override
  public Optional<String> findActiveVersion(String modelName) {
    List<String> results = jdbcTemplate.query(
        FIND_ACTIVE_VERSION_SQL,
        (rs, rowNum) -> rs.getString(1),
        modelName);
    return results.isEmpty() ? Optional.empty() : Optional.ofNullable(results.get(0));
  }
}
