package xiaowu.example.supplieretl.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "supplier.scheduling")
public record SupplierSchedulingProperties(
    boolean enabled,
    long fixedDelayMs,
    int batchSize,
    long leaseSeconds) {

  public SupplierSchedulingProperties {
    fixedDelayMs = fixedDelayMs <= 0 ? 10000 : fixedDelayMs;
    batchSize = batchSize <= 0 ? 50 : batchSize;
    leaseSeconds = leaseSeconds <= 0 ? 30 : leaseSeconds;
  }
}
