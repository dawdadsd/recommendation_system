package xiaowu.backed.domain.recommendation.repository;

import java.time.LocalDateTime;

public interface PreferenceDeltaCleanupRepository {
  int deleteExpiredProcessedDeltas(
      LocalDateTime cutoffTime,
      long safeUpperBoundId,
      int batchSize);
}
