package xiaowu.trainer.interfaces.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import xiaowu.trainer.application.dto.AlsTrainingResultDTO;
import xiaowu.trainer.application.service.AlsTrainingService;

@RestController
@RequestMapping("/api/trainer/als")
@RequiredArgsConstructor
public class AlsTrainingController {

    private final AlsTrainingService alsTrainingService;

    @PostMapping("/train")
    public ResponseEntity<AlsTrainingResultDTO> train() {
        return ResponseEntity.ok(alsTrainingService.trainAndPublish());
    }
}
