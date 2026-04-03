package xiaowu.example.supplieretl.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "supplier.worker")
public record SupplierWorkerProperties(
    boolean enabled,
    int retryDelaySeconds,
    int retryBackoffBaseSeconds,
    int retryBackoffMaxSeconds,
    int retryBackoffMaxJitterMs) {

  public SupplierWorkerProperties {
    retryDelaySeconds = retryDelaySeconds <= 0 ? 45 : retryDelaySeconds;
    retryBackoffBaseSeconds = retryBackoffBaseSeconds <= 0 ? 30 : retryBackoffBaseSeconds;
    retryBackoffMaxSeconds = retryBackoffMaxSeconds <= 0 ? 3600 : retryBackoffMaxSeconds;
    retryBackoffMaxJitterMs = retryBackoffMaxJitterMs < 0 ? 10_000 : retryBackoffMaxJitterMs;
  }
}
