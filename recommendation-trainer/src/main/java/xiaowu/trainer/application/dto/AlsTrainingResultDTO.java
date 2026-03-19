package xiaowu.trainer.application.dto;

public record AlsTrainingResultDTO(
        String modelName,
        String modelVersion,
        String previousVersion,
        int topK,
        long userCount,
        long recallRowCount,
        boolean switched) {
}
