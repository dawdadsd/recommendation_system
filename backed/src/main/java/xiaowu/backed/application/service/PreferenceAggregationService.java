package xiaowu.backed.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.backed.application.dto.PreferenceAggregationResultDTO;
import xiaowu.backed.domain.recommendation.repository.PreferenceAggregationRepository;
import xiaowu.backed.domain.recommendation.repository.RecommendationJobCheckpointRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class PreferenceAggregationService {

  private static final String JOB_NAME = "user-item-preference-aggregation";

  private final PreferenceAggregationRepository preferenceAggregationRepository;
  private final RecommendationJobCheckpointRepository checkpointRepository;

  @Transactional
  public PreferenceAggregationResultDTO aggregateDeltas() {
    long upperBoundId = preferenceAggregationRepository.findMaxDeltaId();
    long lastProcessedId = checkpointRepository.findLastProcessedId(JOB_NAME);

    if (upperBoundId <= lastProcessedId) {
      log.info("[Preference-Aggregation] no new delta rows, lastProcessedId={}", lastProcessedId);
      return new PreferenceAggregationResultDTO(
          JOB_NAME,
          lastProcessedId,
          upperBoundId,
          0,
          false);
    }

    int affectedRows = preferenceAggregationRepository.aggregateDeltaRange(
        lastProcessedId,
        upperBoundId);

    checkpointRepository.saveLastProcessedId(JOB_NAME, upperBoundId);

    log.info(
        "[Preference-Aggregation] aggregated delta rows, range=({}, {}], affectedRows={}",
        lastProcessedId,
        upperBoundId,
        affectedRows);

    return new PreferenceAggregationResultDTO(
        JOB_NAME,
        lastProcessedId,
        upperBoundId,
        affectedRows,
        true);
  }
}
