package xiaowu.backed.application.service;

import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.backed.application.dto.BehaviorEventDTO;
import xiaowu.backed.infrastructure.kafka.BehaviorEventProducer;

/**
 * 流式数据模拟器：以指定频率持续生成随机用户行为事件并写入 Kafka
 * 用户池 userId 1~100，商品池 itemId 1~500
 *
 * @author xiaowu
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamSimulatorService {

    private final BehaviorEventProducer producer;

    private static final List<String> BEHAVIOR_TYPES = List.of("VIEW", "CLICK", "ADD_TO_CART", "PURCHASE", "RATE");

    private static final List<String> DEVICES = List.of("iOS-iPhone15", "Android-Pixel7", "Web-Chrome", "Web-Safari",
            "Android-Samsung");

    private final Random random = new Random();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong total = new AtomicLong(0);

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> taskFuture;

    public synchronized void start(int eventsPerSecond) {
        if (running.get()) {
            log.warn("[Simulator] 已在运行中，请先停止再重新启动");
            return;
        }

        total.set(0);
        int delay = Math.max(1, 1000 / eventsPerSecond);

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "event-simulator");
            t.setDaemon(true);
            return t;
        });
        taskFuture = scheduler.scheduleAtFixedRate(
                this::sendRandomEvent, 0, delay, TimeUnit.MILLISECONDS);

        running.set(true);
        log.info("[Simulator] 已启动：{}条/秒（发送间隔{}ms）", eventsPerSecond, delay);
    }

    public synchronized void stop() {
        if (!running.get()) {
            log.warn("[Simulator] 未在运行");
            return;
        }
        if (taskFuture != null) {
            taskFuture.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        running.set(false);
        log.info("[Simulator] 已停止，累计发送 {} 条事件", total.get());
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getTotalSent() {
        return total.get();
    }

    private void sendRandomEvent() {
        try {
            BehaviorEventDTO event = buildRandomEvent();
            producer.sendBehaviorEvent(event);
            long count = total.incrementAndGet();
            if (count % 100 == 0) {
                log.info("[Simulator] 已发送 {} 条事件", count);
            }
        } catch (Exception e) {
            log.error("[Simulator] 发送事件失败: {}", e.getMessage());
        }
    }

    private BehaviorEventDTO buildRandomEvent() {
        long userId = random.nextLong(100) + 1;
        long itemId = random.nextLong(500) + 1;
        String behaviorType = BEHAVIOR_TYPES.get(random.nextInt(BEHAVIOR_TYPES.size()));
        String device = DEVICES.get(random.nextInt(DEVICES.size()));
        Double rating = "RATE".equals(behaviorType)
                ? 1.0 + random.nextDouble() * 4.0
                : null;

        return BehaviorEventDTO.builder()
                .eventId(UUID.randomUUID().toString())
                .userId(userId)
                .itemId(itemId)
                .behaviorType(behaviorType)
                .rating(rating)
                .sessionId("sess-" + UUID.randomUUID().toString().substring(0, 8))
                .deviceInfo(device)
                .timestamp(Instant.now())
                .build();
    }
}
