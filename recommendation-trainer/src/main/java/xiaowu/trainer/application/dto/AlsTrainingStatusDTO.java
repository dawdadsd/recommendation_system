package xiaowu.trainer.application.dto;

import java.time.LocalDateTime;

/**
 * @author xiaowu
 */
public record AlsTrainingStatusDTO(
    String modelName,
    String activeModelVersion,
    String trainingVersion,
    State state,
    String step,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    String message) {

  public enum State {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED
  }
}
