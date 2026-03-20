package xiaowu.trainer.application.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.trainer.application.dto.RecallCleanupResultDTO;
import xiaowu.trainer.domain.recommendation.repository.RecommendationModelVersionRepository;
import xiaowu.trainer.domain.recommendation.repository.UserCfRecallWriteRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecallCleanupService {

    @Value("${recommendation.als.model-name:user-cf-als}")
    private String modelName;

    @Value("${recommendation.recall-cleanup.batch-size:5000}")
    private int batchSize;

    private final RecommendationModelVersionRepository modelVersionRepository;
    private final UserCfRecallWriteRepository userCfRecallWriteRepository;

    public RecallCleanupResultDTO cleanupOldVersions() {
        List<String> keepVersions = new ArrayList<>();

        modelVersionRepository.findCurrentVersion(modelName)
                .ifPresent(keepVersions::add);
        modelVersionRepository.findPreviousVersion(modelName)
                .ifPresent(keepVersions::add);

        if (keepVersions.isEmpty()) {
            log.info("[Recall-Cleanup] no active model version found for {}, skip cleanup", modelName);
            return new RecallCleanupResultDTO(modelName, keepVersions, batchSize, 0, 0, false);
        }

        long deletedRows = 0;
        int rounds = 0;
        while (true) {
            int deleted = userCfRecallWriteRepository.deleteByModelVersionNotIn(keepVersions, batchSize);
            if (deleted == 0) {
                break;
            }
            deletedRows += deleted;
            rounds++;
            if (deleted < batchSize) {
                break;
            }
        }

        log.info("[Recall-Cleanup] finished, modelName={}, keptVersions={}, rounds={}, deletedRows={}",
                modelName, keepVersions, rounds, deletedRows);

        return new RecallCleanupResultDTO(modelName, keepVersions, batchSize, rounds, deletedRows, deletedRows > 0);
    }
}
