package xiaowu.backed.domain.recommendation.repository;

import java.util.Optional;

public interface ModelVersionReadRepository {

  Optional<String> findActiveVersion(String modelName);
}
