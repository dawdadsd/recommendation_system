package xiaowu.example.supplieretl.datasource.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.supplieretl.datasource.application.model.ConnectionTestResult;
import xiaowu.example.supplieretl.datasource.application.port.ConnectionTester;
import xiaowu.example.supplieretl.datasource.application.support.DataSourceConfigMapper;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnection;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnectionTestLog;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.KafkaDataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.repository.DataSourceConnectionRepository;
import xiaowu.example.supplieretl.datasource.domain.repository.DataSourceConnectionTestLogRepository;

@ExtendWith(MockitoExtension.class)
class DataSourceConnectionTestApplicationServiceTest {

  @Mock
  private DataSourceConnectionRepository connectionRepository;

  @Mock
  private DataSourceConnectionTestLogRepository testLogRepository;

  private DataSourceConnectionTestApplicationService applicationService;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    applicationService = new DataSourceConnectionTestApplicationService(
        connectionRepository,
        testLogRepository,
        new DataSourceConfigMapper(objectMapper),
        List.of(new StubKafkaConnectionTester()));
  }

  @Test
  void testConnectionShouldDispatchByType() throws Exception {
    ConnectionTestResult result = applicationService.testConnection(
        new DataSourceConnectionTestApplicationService.TestConnectionCommand(
            DataSourceType.KAFKA,
            objectMapper.readTree("""
                {
                  "bootstrapServers": "localhost:9092",
                  "topic": "supplier.raw.data"
                }
                """)));

    assertThat(result.success()).isTrue();
    assertThat(result.detail()).containsEntry("topic", "supplier.raw.data");
  }

  @Test
  void testSavedConnectionShouldPersistTestLog() {
    DataSourceConnection connection = DataSourceConnection.restore(
        7L,
        "local-kafka",
        "saved connection",
        DataSourceType.KAFKA,
        new KafkaDataSourceConfig("localhost:9092", "supplier.raw.data", null),
        LocalDateTime.now(),
        LocalDateTime.now());
    when(connectionRepository.findById(7L)).thenReturn(Optional.of(connection));
    when(testLogRepository.save(any(DataSourceConnectionTestLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

    ConnectionTestResult result = applicationService.testSavedConnection(7L);

    assertThat(result.success()).isTrue();
    ArgumentCaptor<DataSourceConnectionTestLog> captor = ArgumentCaptor.forClass(DataSourceConnectionTestLog.class);
    verify(testLogRepository).save(captor.capture());
    assertThat(captor.getValue().getConnectionId()).isEqualTo(7L);
    assertThat(captor.getValue().getDataSourceType()).isEqualTo(DataSourceType.KAFKA);
  }

  private static final class StubKafkaConnectionTester implements ConnectionTester {

    @Override
    public DataSourceType supports() {
      return DataSourceType.KAFKA;
    }

    @Override
    public ConnectionTestResult test(xiaowu.example.supplieretl.datasource.domain.model.DataSourceConfig config) {
      KafkaDataSourceConfig kafkaConfig = (KafkaDataSourceConfig) config;
      return new ConnectionTestResult(
          true,
          "ok",
          Map.of("topic", kafkaConfig.topic()),
          LocalDateTime.now());
    }
  }
}
