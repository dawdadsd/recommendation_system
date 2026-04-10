package xiaowu.example.supplieretl.datasource.infrastructure.test;

import java.time.Duration;
import java.util.Map;

import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.stereotype.Component;

import xiaowu.example.supplieretl.datasource.application.model.ConnectionTestResult;
import xiaowu.example.supplieretl.datasource.application.port.ConnectionTester;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.DataSourceConfig;
import xiaowu.example.supplieretl.datasource.domain.model.RedisDataSourceConfig;

@Component
public class RedisConnectionTester implements ConnectionTester {

  @Override
  public DataSourceType supports() {
    return DataSourceType.REDIS;
  }

  @Override
  public ConnectionTestResult test(DataSourceConfig config) {
    RedisDataSourceConfig redisConfig = cast(config, RedisDataSourceConfig.class, DataSourceType.REDIS);

    RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
        redisConfig.host(),
        redisConfig.port());
    standalone.setDatabase(redisConfig.database());
    if (redisConfig.password() != null) {
      standalone.setPassword(RedisPassword.of(redisConfig.password()));
    }

    LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
        .commandTimeout(Duration.ofSeconds(5))
        .shutdownTimeout(Duration.ofSeconds(1))
        .build();

    LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(standalone, clientConfig);
    try {
      connectionFactory.afterPropertiesSet();
      try (RedisConnection connection = connectionFactory.getConnection()) {
        String pong = connection.ping();
        boolean success = "PONG".equalsIgnoreCase(pong);
        Map<String, Object> detail = Map.of(
            "host", redisConfig.host(),
            "port", redisConfig.port(),
            "database", redisConfig.database(),
            "keyPattern", redisConfig.keyPattern(),
            "ping", pong == null ? "" : pong);

        if (!success) {
          return ConnectionTestResult.failure("Redis connection test failed: ping response is invalid", detail);
        }
        return ConnectionTestResult.success("Redis connection test passed", detail);
      }
    } catch (Exception ex) {
      return ConnectionTestResult.failure(
          "Redis connection test failed: " + ex.getMessage(),
          Map.of(
              "host", redisConfig.host(),
              "port", redisConfig.port(),
              "database", redisConfig.database(),
              "errorType", ex.getClass().getSimpleName()));
    } finally {
      connectionFactory.destroy();
    }
  }

  private static <T extends DataSourceConfig> T cast(
      DataSourceConfig config,
      Class<T> expectedType,
      DataSourceType sourceType) {
    if (!expectedType.isInstance(config)) {
      throw new IllegalArgumentException("Invalid config type for " + sourceType);
    }
    return expectedType.cast(config);
  }
}
