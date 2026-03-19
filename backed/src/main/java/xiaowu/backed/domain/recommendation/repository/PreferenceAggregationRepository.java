package xiaowu.backed.domain.recommendation.repository;

public interface PreferenceAggregationRepository {
  long findMaxDeltaId();

  int aggregateDeltaRange(long lowerExclusiveId, long upperInclusiveId);

}
