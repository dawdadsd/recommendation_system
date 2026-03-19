package xiaowu.backed.interfaces.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import xiaowu.backed.application.dto.PreferenceAggregationResultDTO;
import xiaowu.backed.application.dto.PreferenceDeltaCleanupResultDTO;
import xiaowu.backed.application.service.PreferenceAggregationService;
import xiaowu.backed.application.service.PreferenceDeltaCleanUpService;

@RestController
@RequestMapping("/api/recommendation/maintenance")
@RequiredArgsConstructor
public class RecommendationMaintenanceController {

  private final PreferenceAggregationService preferenceAggregationService;

  private final PreferenceDeltaCleanUpService preferenceDeltaCleanUpService;

  @PostMapping("/aggregate-preferences")
  public ResponseEntity<PreferenceAggregationResultDTO> aggregatePreferences() {
    return ResponseEntity.ok(preferenceAggregationService.aggregateDeltas());
  }

  @PostMapping("/cleanup-preference-delta")
  public ResponseEntity<PreferenceDeltaCleanupResultDTO> cleanupPreferenceDelta() {
    return ResponseEntity.ok(preferenceDeltaCleanUpService.cleanupExpiredDeltas());
  }

}
