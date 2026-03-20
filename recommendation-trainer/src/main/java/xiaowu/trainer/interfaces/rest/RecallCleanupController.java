package xiaowu.trainer.interfaces.rest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xiaowu.trainer.application.dto.RecallCleanupResultDTO;
import xiaowu.trainer.application.service.RecallCleanupService;

@RestController
@RequestMapping("/api/trainer/recall")
@RequiredArgsConstructor
public class RecallCleanupController {

    private final RecallCleanupService recallCleanupService;

    @PostMapping("/cleanup")
    public Mono<RecallCleanupResultDTO> cleanup() {
        return Mono.fromCallable(recallCleanupService::cleanupOldVersions)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
