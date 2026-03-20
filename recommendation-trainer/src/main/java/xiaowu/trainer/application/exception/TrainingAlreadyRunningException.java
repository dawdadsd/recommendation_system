package xiaowu.trainer.application.exception;

public class TrainingAlreadyRunningException extends RuntimeException {
  public TrainingAlreadyRunningException(String message) {
    super(message);
  }
}
