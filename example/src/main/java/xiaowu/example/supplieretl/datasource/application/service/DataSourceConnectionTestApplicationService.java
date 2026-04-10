package xiaowu.example.supplieretl.datasource.application.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import xiaowu.example.supplieretl.datasource.application.model.ConnectionTestResult;
import xiaowu.example.supplieretl.datasource.application.port.ConnectionTester;
import xiaowu.example.supplieretl.datasource.application.support.DataSourceConfigMapper;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnection;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnectionTestLog;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.DataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.repository.DataSourceConnectionRepository;
import xiaowu.example.supplieretl.datasource.domain.repository.DataSourceConnectionTestLogRepository;

@Service
public class DataSourceConnectionTestApplicationService {

  private final DataSourceConnectionRepository connectionRepository;
  private final DataSourceConnectionTestLogRepository testLogRepository;
  private final DataSourceConfigMapper configMapper;
  private final Map<DataSourceType, ConnectionTester> testers;

  public DataSourceConnectionTestApplicationService(
      DataSourceConnectionRepository connectionRepository,
      DataSourceConnectionTestLogRepository testLogRepository,
      DataSourceConfigMapper configMapper,
      List<ConnectionTester> testers) {
    this.connectionRepository = Objects.requireNonNull(connectionRepository, "connectionRepository must not be null");
    this.testLogRepository = Objects.requireNonNull(testLogRepository, "testLogRepository must not be null");
    this.configMapper = Objects.requireNonNull(configMapper, "configMapper must not be null");
    this.testers = indexTesters(testers);
  }

  public ConnectionTestResult testConnection(TestConnectionCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    Objects.requireNonNull(command.type(), "type must not be null");
    Objects.requireNonNull(command.config(), "config must not be null");

    DataSourceConfig config = configMapper.fromJsonNode(command.type(), command.config());
    return requireTester(command.type()).test(config);
  }

  public ConnectionTestResult testSavedConnection(Long connectionId) {
    DataSourceConnection connection = connectionRepository.findById(connectionId)
        .orElseThrow(() -> new IllegalStateException("Data source connection not found: " + connectionId));

    ConnectionTestResult result = requireTester(connection.getType()).test(connection.getConfig());
    testLogRepository.save(DataSourceConnectionTestLog.create(
        connection.getId(),
        connection.getType(),
        result.success(),
        result.message(),
        result.detail(),
        result.testedAt()));
    return result;
  }

  public List<DataSourceConnectionTestLog> recentLogs(Long connectionId, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return testLogRepository.findRecentByConnectionId(connectionId, limit);
  }

  private ConnectionTester requireTester(DataSourceType type) {
    ConnectionTester tester = testers.get(type);
    if (tester == null) {
      throw new IllegalStateException("No connection tester configured for type: " + type);
    }
    return tester;
  }

  private static Map<DataSourceType, ConnectionTester> indexTesters(List<ConnectionTester> testers) {
    Map<DataSourceType, ConnectionTester> indexed = new EnumMap<>(DataSourceType.class);
    for (ConnectionTester tester : testers) {
      ConnectionTester previous = indexed.put(tester.supports(), tester);
      if (previous != null) {
        throw new IllegalStateException("Duplicate connection tester for type: " + tester.supports());
      }
    }
    return Map.copyOf(indexed);
  }

  public record TestConnectionCommand(
      DataSourceType type,
      JsonNode config) {
  }
}
