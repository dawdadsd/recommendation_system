package xiaowu.backed.application.scheduler;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.backed.application.service.PreferenceAggregationService;
import xiaowu.backed.application.service.PreferenceDeltaCleanUpService;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecommendationJobScheduler {
  private final PreferenceAggregationService preferenceAggregationService;

  private final PreferenceDeltaCleanUpService preferenceDeltaCleanUpService;

  private static AtomicBoolean aggregatePreferencesRunning = new AtomicBoolean(false);

  private static AtomicBoolean cleanupPreferenceDeltaRunning = new AtomicBoolean(false);

  @Value("${recommendation.jobs.aggregate-preferences.enabled:true}")
  private boolean aggregatePreferencesEnabled;

  @Value("${recommendation.jobs.cleanup-preference-delta.enabled:true}")
  private boolean cleanupPreferenceDeltaEnabled;

  @Scheduled(initialDelayString = "${recommendation.jobs.aggregate-preferences.initial-delay-ms:60000}", fixedDelayString = "${recommendation.jobs.aggregate-preferences.fixed-delay-ms:3600000}")
  public void aggregatePreferences() {
    if (!aggregatePreferencesEnabled) {
      log.info("[Recommendation-Job] aggregate-preferences job is disabled, skipping");
      return;
    }
    if (!aggregatePreferencesRunning.compareAndSet(false, true)) {
      log.warn("[Recommendation-Job] aggregate-preferences is already running, skipping this round");
      return;
    }
    try {
      log.info("[Recommendation-Job] aggregate-preferences started");
      var result = preferenceAggregationService.aggregateDeltas();
      log.info(
          "[Recommendation-Job] aggregate-preferences finished, updated={}, range=({}, {}], affectedRows={}",
          result.updated(),
          result.lastProcessedId(),
          result.upperBoundId(),
          result.affectedRows());
    } catch (Exception e) {
      log.error("[Recommendation-Job] Error occurred while aggregating preferences", e);
    } finally {
      aggregatePreferencesRunning.set(false);
    }
  }

  @Scheduled(cron = "${recommendation.jobs.cleanup-preference-delta.cron:0 10 3 * * *}", zone = "${recommendation.jobs.time-zone:Asia/Shanghai}")
  public void cleanupPreferenceDelta() {
    if (!cleanupPreferenceDeltaEnabled) {
      log.info("[Recommendation-Job] cleanup-preference-delta job is disabled, skipping");
      return;
    }

    if (!cleanupPreferenceDeltaRunning.compareAndSet(false, true)) {
      log.warn("[Recommendation-Job] cleanup-preference-delta is already running, skipping this round");
      return;
    }

    try {
      log.info("[Recommendation-Job] cleanup-preference-delta started");
      var result = preferenceDeltaCleanUpService.cleanupExpiredDeltas();
      log.info(
          "[Recommendation-Job] cleanup-preference-delta finished, updated={}, cutoffTime={}, safeUpperBoundId={}, deletedRows={}, rounds={}",
          result.updated(),
          result.cutoffTime(),
          result.safeUpperBoundId(),
          result.deletedRows(),
          result.rounds());
    } catch (Exception e) {
      log.error("[Recommendation-Job] Error occurred while cleaning preference delta", e);
    } finally {
      cleanupPreferenceDeltaRunning.set(false);
    }
  }

}
