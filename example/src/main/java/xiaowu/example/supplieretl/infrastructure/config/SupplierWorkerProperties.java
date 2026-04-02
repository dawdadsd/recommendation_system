package xiaowu.example.supplieretl.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "supplier.worker")
public record SupplierWorkerProperties(
    boolean enabled,
    int retryDelaySeconds) {

  public SupplierWorkerProperties {
    retryDelaySeconds = retryDelaySeconds <= 0 ? 45 : retryDelaySeconds;
  }
}
