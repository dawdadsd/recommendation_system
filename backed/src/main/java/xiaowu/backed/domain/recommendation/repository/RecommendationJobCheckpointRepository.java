package xiaowu.backed.domain.recommendation.repository;

public interface RecommendationJobCheckpointRepository {
  long findLastProcessedId(String jobName);

  void saveLastProcessedId(String jobName, long lastProcessedId);
}
