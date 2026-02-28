package xiaowu.backed.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import xiaowu.backed.application.dto.BehaviorEventDTO;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka 行为事件生产者：将 BehaviorEventDTO 序列化为 JSON 发送到指定 Topic
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
        this.objectMapper.disable(
                com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void sendBehaviorEvent(BehaviorEventDTO event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            String key = String.valueOf(event.getUserId());

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(userEventsTopic, key, json);

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
