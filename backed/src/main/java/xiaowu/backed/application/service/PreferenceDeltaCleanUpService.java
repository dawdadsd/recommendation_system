package xiaowu.backed.application.service;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.backed.application.dto.PreferenceDeltaCleanupResultDTO;
import xiaowu.backed.domain.recommendation.repository.PreferenceDeltaCleanupRepository;
import xiaowu.backed.domain.recommendation.repository.RecommendationJobCheckpointRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class PreferenceDeltaCleanUpService {
  private static final String AGGREGATION_JOB_NAME = "user-item-preference-aggregation";

  private static final String CLEANUP_JOB_NAME = "cleanup-user-item-preference-delta";

  @Value("${recommendation.jobs.cleanup-preference-delta.retention-days:30}")
  private int retentionDays;

  @Value("${recommendation.jobs.cleanup-preference-delta.batch-size:5000}")
  private int batchSize;

  private final PreferenceDeltaCleanupRepository preferenceDeltaCleanupRepository;
  private final RecommendationJobCheckpointRepository checkpointRepository;

  public PreferenceDeltaCleanupResultDTO cleanupExpiredDeltas() {
    if (retentionDays <= 0) {
      throw new IllegalArgumentException(
          "数据保留天数必须为正数");
    }
    if (batchSize <= 0) {
      throw new IllegalArgumentException(
          "批处理大小必须为正数");
    }
    long safeUpperBoundId = checkpointRepository.findLastProcessedId(AGGREGATION_JOB_NAME);
    LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);

    if (safeUpperBoundId <= 0) {
      log.info("[Preference-Delta-Cleanup] no aggregation checkpoint found, skip cleanup");
      return new PreferenceDeltaCleanupResultDTO(
          CLEANUP_JOB_NAME,
          cutoffTime,
          safeUpperBoundId,
          batchSize,
          0,
          0,
          false);
    }
    long deletedRows = 0;
    int rounds = 0;
    while (true) {
      int deleted = preferenceDeltaCleanupRepository.deleteExpiredProcessedDeltas(cutoffTime, safeUpperBoundId,
          batchSize);
      if (deleted == 0) {
        break;
      }
      deletedRows += deleted;
      rounds++;
      if (deleted < batchSize) {
        break;
      }
    }
    log.info(
        "[Preference-Delta-Cleanup] finished, cutoffTime={}, safeUpperBoundId={}, rounds={}, deletedRows={}",
        cutoffTime,
        safeUpperBoundId,
        rounds,
        deletedRows);
    return new PreferenceDeltaCleanupResultDTO(
        CLEANUP_JOB_NAME,
        cutoffTime,
        safeUpperBoundId,
        batchSize,
        rounds,
        deletedRows,
        deletedRows > 0);

  }
}
