package xiaowu.example.supplieretl.infrastructure.kafka.publisher;

import java.util.Objects;

import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.supplieretl.application.port.PullTaskPublisher;
import xiaowu.example.supplieretl.infrastructure.kafka.SupplierKafkaProperties;

/**
 * Kafka implementation of {@link PullTaskPublisher}.
 */
public class KafkaPullTaskPublisher implements PullTaskPublisher {

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final SupplierKafkaProperties properties;

  public KafkaPullTaskPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      SupplierKafkaProperties properties) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  @Override
  public void publishPullRequested(PullRequestedEvent event) {
    Objects.requireNonNull(event, "event");
    try {
      String payload = objectMapper.writeValueAsString(event);
      // send params : topic,key,payload
      kafkaTemplate.send(
          properties.topic().pullRequest(),
          String.valueOf(event.supplierId()),
          payload).join();
    } catch (Exception ex) {
      throw new RuntimeException("Failed to publish supplier pull task", ex);
    }
  }
}
