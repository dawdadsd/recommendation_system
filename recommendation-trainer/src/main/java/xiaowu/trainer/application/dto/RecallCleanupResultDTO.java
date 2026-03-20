package xiaowu.trainer.application.dto;

import java.util.List;

public record RecallCleanupResultDTO(
        String modelName,
        List<String> keptVersions,
        int batchSize,
        int rounds,
        long deletedRows,
        boolean cleaned) {
}
