package xiaowu.backed.infrastructure.kafka;

import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import lombok.extern.slf4j.Slf4j;
import xiaowu.backed.application.dto.BehaviorEventDTO;

/**
 * Kafka 行为事件生产者：将 BehaviorEventDTO 序列化为 JSON 发送到指定 Topic
 *
 * @author xiaowu
 */
@Slf4j
@Service
public class BehaviorEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kafka.topic.user-events}")
    private String userEventsTopic;

    public BehaviorEventProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        /**
         * 关闭Jackson在序列化Instant时默认转换为时间戳的行为，改为ISO-8601字符串格式，便于Spark解析和人类阅读
         * 默认可能会转为数字,关闭后会转为字符串,例如 "2024-06-01T12:34:56.789Z" 而不是 1712133296789
         */
        this.objectMapper.disable(
                SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void sendBehaviorEvent(BehaviorEventDTO event) {
        try {
            /**
             * 将对象转换为JSON字符串
             */
            String json = objectMapper.writeValueAsString(event);
            /**
             * 如果没有key,Kafka会使用轮询的方式将消息分布到不同分区,这样来自同一用户的事件可能会分布在不同分区,导致消费时无法保证顺序性和局部性,因此我们使用userId作为key,确保同一用户的事件被发送到同一分区,这样消费者在处理时就能保证同一用户的事件是有序的
             */
            String key = String.valueOf(event.getUserId());
            /**
             * 异步发送Kafka消息并记录发送结果
             */
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(userEventsTopic, key, json);
            /**
             * 注册回调,等发送结果回来在执行
             */
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("[Kafka] 发送失败 userId={} itemId={} error={}",
                            event.getUserId(), event.getItemId(), ex.getMessage());
                } else {
                    log.debug("[Kafka] 发送成功 userId={} itemId={} partition={} offset={}",
                            event.getUserId(), event.getItemId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });
        } catch (JsonProcessingException e) {
            log.error("[Kafka] JSON 序列化失败: {}", e.getMessage());
        }
    }
}
