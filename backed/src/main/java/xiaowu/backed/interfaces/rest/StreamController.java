package xiaowu.backed.interfaces.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import xiaowu.backed.application.dto.BehaviorEventDTO;
import xiaowu.backed.application.service.StreamSimulatorService;
import xiaowu.backed.infrastructure.kafka.BehaviorEventProducer;
import xiaowu.backed.infrastructure.spark.BehaviorStreamProcessor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 流式 Demo REST 控制器
 * POST /api/stream/start   启动模拟器 + Spark Streaming
 * POST /api/stream/stop    停止所有流处理
 * GET  /api/stream/status  查看运行状态
 * POST /api/stream/event   手动发送单条事件（调试）
 * @author xiaowu
 */
@RestController
@RequestMapping("/api/stream")
@RequiredArgsConstructor
public class StreamController {

    private final StreamSimulatorService  simulatorService;
    private final BehaviorStreamProcessor streamProcessor;
    private final BehaviorEventProducer   eventProducer;

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(
            @RequestParam(defaultValue = "5") int eventsPerSecond) {

        if (!streamProcessor.isRunning()) {
            streamProcessor.start();
        }
        simulatorService.start(eventsPerSecond);

        return ResponseEntity.ok(Map.of(
                "code",    200,
                "message", "流式 Demo 已启动",
                "detail",  Map.of(
                        "eventsPerSecond", eventsPerSecond,
                        "sparkRunning",    streamProcessor.isRunning(),
                        "simulatorRunning", simulatorService.isRunning()
                )
        ));
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        simulatorService.stop();
        streamProcessor.stop();

        return ResponseEntity.ok(Map.of(
                "code",       200,
                "message",    "流式 Demo 已停止",
                "totalSent",  simulatorService.getTotalSent()
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "simulatorRunning",  simulatorService.isRunning(),
                "totalSent",         simulatorService.getTotalSent(),
                "sparkQueryRunning", streamProcessor.isRunning()
        ));
    }

    @PostMapping("/event")
    public ResponseEntity<Map<String, Object>> sendEvent(
            @RequestBody BehaviorEventDTO event) {

        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        if (event.getTimestamp() == null) {
            event.setTimestamp(Instant.now());
        }

        eventProducer.sendBehaviorEvent(event);

        return ResponseEntity.ok(Map.of(
                "code",    200,
                "message", "事件已提交到 Kafka（异步）",
                "event",   event
        ));
    }
}
