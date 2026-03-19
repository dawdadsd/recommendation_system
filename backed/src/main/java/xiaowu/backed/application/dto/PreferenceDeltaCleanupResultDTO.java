package xiaowu.backed.application.dto;

import java.time.LocalDateTime;

public record PreferenceDeltaCleanupResultDTO(
    String jobName,
    LocalDateTime cutoffTime,
    long safeUpperBoundId,
    int batchSize,
    int rounds,
    long deletedRows,
    boolean updated) {
}
