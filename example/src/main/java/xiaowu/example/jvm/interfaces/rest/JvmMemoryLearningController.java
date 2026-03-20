package xiaowu.example.jvm.interfaces.rest;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import xiaowu.example.jvm.application.service.JvmMemoryLearningService;
import xiaowu.example.jvm.application.service.JvmMemoryLearningService.GcSimulationReport;
import xiaowu.example.jvm.application.service.JvmMemoryLearningService.JvmRuntimeLayout;

/**
 * JVM 学习控制器。
 *
 * <p>这个控制器的目标不是做业务接口，而是把学习结果稳定暴露出来，方便你启动
 * Application 后直接访问。
 *
 * <p>建议启动后先看：
 *
 * <p>1. {@code /api/examples/jvm/layout}
 * 先观察真实 JVM 内存布局。
 *
 * <p>2. {@code /api/examples/jvm/gc/mark-sweep}
 * 再看一轮教学版标记清理过程。
 *
 * <p>3. {@code /api/examples/jvm/overview}
 * 最后看一个聚合视图，方便整体串联。
 */
@RestController
@RequestMapping("/api/examples/jvm")
@RequiredArgsConstructor
public class JvmMemoryLearningController {

    private final JvmMemoryLearningService jvmMemoryLearningService;

    /**
     * 查看当前 ExampleApplication 进程的真实 JVM 内存布局。
     */
    @GetMapping("/layout")
    public JvmRuntimeLayout layout() {
        return jvmMemoryLearningService.captureRuntimeLayout();
    }

    /**
     * 查看一轮教学版标记-清理 GC 结果。
     */
    @GetMapping("/gc/mark-sweep")
    public GcSimulationReport markSweep() {
        return jvmMemoryLearningService.simulateMarkSweepForTrainerJob();
    }

    /**
     * 提供一个更适合初学时总览的聚合视图。
     */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        JvmRuntimeLayout layout = jvmMemoryLearningService.captureRuntimeLayout();
        GcSimulationReport gcReport = jvmMemoryLearningService.simulateMarkSweepForTrainerJob();

        return Map.of(
                "capturedAt", layout.capturedAt(),
                "heapUsedBytes", layout.heap().usedBytes(),
                "nonHeapUsedBytes", layout.nonHeap().usedBytes(),
                "threadCount", layout.liveThreadCount(),
                "gcSummary", gcReport.toSummaryMap(),
                "learningNotes", layout.learningNotes());
    }
}
