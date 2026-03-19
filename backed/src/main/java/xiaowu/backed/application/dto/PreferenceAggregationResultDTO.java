package xiaowu.backed.application.dto;

public record PreferenceAggregationResultDTO(
    String jobName,
    long lastProcessedId,
    long upperBoundId,
    long affectedRows,
    boolean updated) {

}
