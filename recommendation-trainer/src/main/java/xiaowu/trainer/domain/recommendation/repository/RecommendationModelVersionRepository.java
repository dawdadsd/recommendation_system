package xiaowu.trainer.domain.recommendation.repository;

import java.util.Optional;

public interface RecommendationModelVersionRepository {

    Optional<String> findCurrentVersion(String modelName);

    void switchVersion(String modelName, String newVersion, String previousVersion, String status);
}
