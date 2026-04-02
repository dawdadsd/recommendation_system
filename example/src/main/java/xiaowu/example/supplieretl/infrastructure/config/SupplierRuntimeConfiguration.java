package xiaowu.example.supplieretl.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import xiaowu.example.supplieretl.application.port.SupplierPullClient;
import xiaowu.example.supplieretl.application.service.SupplierPullExecutionApplicationService;
import xiaowu.example.supplieretl.domain.repository.SupplierConnectionRepository;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({ SupplierSchedulingProperties.class, SupplierWorkerProperties.class })
public class SupplierRuntimeConfiguration {

  @Bean
  @ConditionalOnBean(SupplierPullClient.class)
  SupplierPullExecutionApplicationService supplierPullExecutionApplicationService(
      SupplierConnectionRepository supplierConnectionRepository,
      SupplierPullClient supplierPullClient) {
    return new SupplierPullExecutionApplicationService(
        supplierConnectionRepository,
        supplierPullClient);
  }
}
