package xiaowu.backed.infrastructure.kafka;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import xiaowu.backed.application.dto.BehaviorEventDTO;

/**
 * Kafka 生产者并发压测：模拟 10 万用户同时发送行为事件
 *
 * 前置条件：Kafka 必须在 localhost:9092 运行
 * 运行方式：mvn test -Dtest=KafkaConcurrentLoadTest
 *
 * @author xiaowu
 */
@SpringBootTest
class KafkaConcurrentLoadTest {

    @Autowired
    private BehaviorEventProducer producer;

    private static final int TOTAL_EVENTS = 100_000;
    private static final int THREAD_POOL_SIZE = 200;

    private static final List<String> BEHAVIOR_TYPES = List.of("VIEW", "CLICK", "ADD_TO_CART", "PURCHASE", "RATE");
    private static final List<String> DEVICES = List.of("iOS-iPhone15", "Android-Pixel7", "Web-Chrome", "Web-Safari", "Android-Samsung");

    @Test
    void concurrentLoadTest() throws InterruptedException {
        // 1. 预生成 10 万条事件
        System.out.println("========== 预生成 " + TOTAL_EVENTS + " 条事件 ==========");
        List<BehaviorEventDTO> events = preGenerateEvents(TOTAL_EVENTS);
        System.out.println("事件预生成完成");

        // 2. 准备并发基础设施
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch startGate = new CountDownLatch(1);   // 所有线程同时起跑
        CountDownLatch endGate = new CountDownLatch(TOTAL_EVENTS);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);

        // 3. 提交所有任务（线程在 startGate 处等待）
        for (BehaviorEventDTO event : events) {
            executor.submit(() -> {
                try {
                    startGate.await(); // 等待统一发令
                    producer.sendBehaviorEvent(event);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    endGate.countDown();
                }
            });
        }

        // 4. 发令起跑，计时开始
        System.out.println("========== 压测开始 ==========");
        long startTime = System.currentTimeMillis();
        startGate.countDown();

        // 5. 等待所有任务完成
        endGate.await();
        long elapsed = System.currentTimeMillis() - startTime;

        // 6. 关闭线程池
        executor.shutdown();

        // 7. 打印压测报告
        double seconds = elapsed / 1000.0;
        double throughput = TOTAL_EVENTS / seconds;

        System.out.println();
        System.out.println("========== 压测结果 ==========");
        System.out.printf("总事件数:   %,d%n", TOTAL_EVENTS);
        System.out.printf("成功发送:   %,d%n", successCount.get());
        System.out.printf("发送失败:   %,d%n", failCount.get());
        System.out.printf("总耗时:     %.2f 秒%n", seconds);
        System.out.printf("吞吐量:     %,.0f events/sec%n", throughput);
        System.out.println("==============================");
    }

    private List<BehaviorEventDTO> preGenerateEvents(int count) {
        Random random = new Random();
        List<BehaviorEventDTO> events = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            long userId = random.nextLong(100_000) + 1; // 10 万用户
            long itemId = random.nextLong(500) + 1;
            String behaviorType = BEHAVIOR_TYPES.get(random.nextInt(BEHAVIOR_TYPES.size()));
            String device = DEVICES.get(random.nextInt(DEVICES.size()));
            Double rating = "RATE".equals(behaviorType) ? 1.0 + random.nextDouble() * 4.0 : null;

            events.add(BehaviorEventDTO.builder()
                    .eventId(UUID.randomUUID().toString())
                    .userId(userId)
                    .itemId(itemId)
                    .behaviorType(behaviorType)
                    .rating(rating)
                    .sessionId("sess-" + UUID.randomUUID().toString().substring(0, 8))
                    .deviceInfo(device)
                    .timestamp(Instant.now())
                    .build());
        }
        return events;
    }
}
