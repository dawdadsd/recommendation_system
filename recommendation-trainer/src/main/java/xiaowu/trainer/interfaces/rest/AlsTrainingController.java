package xiaowu.trainer.interfaces.rest;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xiaowu.trainer.application.dto.AlsTrainingResultDTO;
import xiaowu.trainer.application.dto.AlsTrainingStatusDTO;
import xiaowu.trainer.application.exception.TrainingAlreadyRunningException;
import xiaowu.trainer.application.service.AlsTrainingService;

@RestController
@RequestMapping("/api/trainer/als")
@RequiredArgsConstructor
public class AlsTrainingController {

    private final AlsTrainingService alsTrainingService;

    @PostMapping("/train")
    public Mono<AlsTrainingResultDTO> train() {
        return Mono.fromCallable(alsTrainingService::trainAndPublish)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/status")
    public AlsTrainingStatusDTO status() {
        return alsTrainingService.getStatus();
    }

    @ExceptionHandler(TrainingAlreadyRunningException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleTrainingAlreadyRunning(TrainingAlreadyRunningException ex) {
        return Map.of("message", ex.getMessage());
    }
}
