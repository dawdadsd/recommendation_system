package xiaowu.example.supplieretl.datasource.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.DataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.model.KafkaDataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.model.MysqlDataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.model.RedisDataSourceConfig;

@Component
public class ConnectionTargetDescriptorResolver {

  public List<ConnectionTargetDescriptor> resolve(DataSourceType type, DataSourceConfig config) {
    return switch (type) {
      case KAFKA -> resolveKafka((KafkaDataSourceConfig) config);
      case MYSQL -> List.of(resolveMysql((MysqlDataSourceConfig) config));
      case REDIS -> List.of(resolveRedis((RedisDataSourceConfig) config));
      case EXCEL -> List.of();
    };
  }

  private List<ConnectionTargetDescriptor> resolveKafka(KafkaDataSourceConfig config) {
    List<ConnectionTargetDescriptor> descriptors = new ArrayList<>();
    for (String server : config.bootstrapServers().split(",")) {
      String trimmed = server.trim();
      if (!trimmed.isEmpty()) {
        descriptors.add(parseAuthority(trimmed, 9092));
      }
    }
    if (descriptors.isEmpty()) {
      throw new IllegalArgumentException("bootstrapServers must not be empty");
    }
    return List.copyOf(descriptors);
  }

  private ConnectionTargetDescriptor resolveMysql(MysqlDataSourceConfig config) {
    String jdbcUrl = config.jdbcUrl();
    if (!jdbcUrl.startsWith("jdbc:mysql://")) {
      throw new IllegalArgumentException("Only jdbc:mysql:// URLs are supported");
    }
    try {
      URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
      String host = uri.getHost();
      int port = uri.getPort() > 0 ? uri.getPort() : 3306;
      if (host == null || host.isBlank()) {
        throw new IllegalArgumentException("Invalid MySQL jdbcUrl host");
      }
      return new ConnectionTargetDescriptor(host, port, jdbcUrl);
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("Invalid MySQL jdbcUrl: " + jdbcUrl, ex);
    }
  }

  private ConnectionTargetDescriptor resolveRedis(RedisDataSourceConfig config) {
    return new ConnectionTargetDescriptor(config.host(), config.port(), config.host() + ":" + config.port());
  }

  private ConnectionTargetDescriptor parseAuthority(String authority, int defaultPort) {
    try {
      URI uri = new URI("tcp://" + authority);
      String host = uri.getHost();
      int port = uri.getPort() > 0 ? uri.getPort() : defaultPort;
      if (host == null || host.isBlank()) {
        throw new IllegalArgumentException("Invalid host: " + authority);
      }
      return new ConnectionTargetDescriptor(host, port, authority);
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("Invalid host: " + authority, ex);
    }
  }
}
