package xiaowu.example.supplieretl.datasource.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.supplieretl.datasource.application.support.DataSourceConfigMapper;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnection;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.KafkaDataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.repository.DataSourceConnectionRepository;

@ExtendWith(MockitoExtension.class)
class DataSourceConnectionApplicationServiceTest {

  @Mock
  private DataSourceConnectionRepository repository;

  private DataSourceConnectionApplicationService applicationService;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    applicationService = new DataSourceConnectionApplicationService(
        repository,
        new DataSourceConfigMapper(objectMapper));
  }

  @Test
  void createConnectionShouldParseKafkaConfigAndSave() throws Exception {
    when(repository.findByConnectionName("local-kafka")).thenReturn(Optional.empty());
    when(repository.save(any(DataSourceConnection.class))).thenAnswer(invocation -> invocation.getArgument(0));

    var command = new DataSourceConnectionApplicationService.CreateConnectionCommand(
        "local-kafka",
        "Local Kafka broker",
        DataSourceType.KAFKA,
        objectMapper.readTree("""
            {
              "bootstrapServers": "localhost:9092",
              "topic": "supplier.raw.data",
              "clientId": "etl-local"
            }
            """));

    DataSourceConnection created = applicationService.createConnection(command);

    assertThat(created.getType()).isEqualTo(DataSourceType.KAFKA);
    assertThat(created.getConfig()).isInstanceOf(KafkaDataSourceConfig.class);
    KafkaDataSourceConfig config = (KafkaDataSourceConfig) created.getConfig();
    assertThat(config.bootstrapServers()).isEqualTo("localhost:9092");
    assertThat(config.topic()).isEqualTo("supplier.raw.data");

    ArgumentCaptor<DataSourceConnection> captor = ArgumentCaptor.forClass(DataSourceConnection.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getConnectionName()).isEqualTo("local-kafka");
  }

  @Test
  void createConnectionShouldRejectDuplicateName() throws Exception {
    when(repository.findByConnectionName("local-kafka")).thenReturn(Optional.of(
        DataSourceConnection.create(
            "local-kafka",
            "Existing connection",
            DataSourceType.KAFKA,
            new KafkaDataSourceConfig("localhost:9092", "supplier.raw.data", null))));

    var command = new DataSourceConnectionApplicationService.CreateConnectionCommand(
        "local-kafka",
        "Another Kafka broker",
        DataSourceType.KAFKA,
        objectMapper.readTree("""
            {
              "bootstrapServers": "localhost:9092",
              "topic": "supplier.raw.data"
            }
            """));

    assertThatThrownBy(() -> applicationService.createConnection(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already exists");
  }
}
