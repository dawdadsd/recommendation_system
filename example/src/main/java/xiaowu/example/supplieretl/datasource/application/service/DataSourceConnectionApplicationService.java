package xiaowu.example.supplieretl.datasource.application.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import xiaowu.example.supplieretl.datasource.application.support.DataSourceConfigMapper;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnection;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.DataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.repository.DataSourceConnectionRepository;

@Service
public class DataSourceConnectionApplicationService {

    private final DataSourceConnectionRepository repository;
    private final DataSourceConfigMapper configMapper;

    public DataSourceConnectionApplicationService(
            DataSourceConnectionRepository repository,
            DataSourceConfigMapper configMapper) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.configMapper = Objects.requireNonNull(configMapper, "configMapper must not be null");
    }

    public List<DataSourceConnection> listConnections() {
        return repository.findAll();
    }

    public DataSourceConnection getConnection(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Data source connection not found: " + id));
    }

  public DataSourceConnection createConnection(CreateConnectionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(command.type(), "type must not be null");
        Objects.requireNonNull(command.config(), "config must not be null");

        repository.findByConnectionName(command.connectionName())
                .ifPresent(existing -> {
                    throw new IllegalStateException(
                            "Data source connection already exists: " + existing.getConnectionName());
                });

        DataSourceConfig config = configMapper.fromJsonNode(command.type(), command.config());
        DataSourceConnection connection = DataSourceConnection.create(
                command.connectionName(),
                command.description(),
                command.type(),
                config);
        return repository.save(connection);
  }

  public DataSourceConfig parseConfig(DataSourceType type, JsonNode config) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(config, "config must not be null");
    return configMapper.fromJsonNode(type, config);
  }

    public List<SupportedDataSourceType> supportedTypes() {
        return List.of(
                new SupportedDataSourceType(
                        DataSourceType.KAFKA.name(),
                        "Kafka broker/topic connection",
                        List.of("bootstrapServers", "topic"),
                        List.of("clientId")),
                new SupportedDataSourceType(
                        DataSourceType.MYSQL.name(),
                        "MySQL database connection",
                        List.of("jdbcUrl", "username"),
                        List.of("password", "driverClassName", "validationQuery")),
                new SupportedDataSourceType(
                        DataSourceType.REDIS.name(),
                        "Redis cache connection",
                        List.of("host"),
                        List.of("port", "database", "password", "keyPattern")),
                new SupportedDataSourceType(
                        DataSourceType.EXCEL.name(),
                        "Excel parsing profile",
                        List.of(),
                        List.of("sheetName", "headerRowIndex", "sampleSize")));
    }

    public record CreateConnectionCommand(
            String connectionName,
            String description,
            DataSourceType type,
            JsonNode config) {
    }

    public record SupportedDataSourceType(
            String type,
            String description,
            List<String> requiredFields,
            List<String> optionalFields) {
    }
}
