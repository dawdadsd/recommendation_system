package xiaowu.example.supplieretl.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import xiaowu.example.supplieretl.application.port.PullTaskPublisher;
import xiaowu.example.supplieretl.application.service.SupplierPullSchedulingApplicationService;
import xiaowu.example.supplieretl.domain.repository.SupplierConnectionRepository;

@Configuration(proxyBeanMethods = false)
public class SupplierSchedulingApplicationConfiguration {

  @Bean
  SupplierPullSchedulingApplicationService supplierPullSchedulingApplicationService(
      SupplierConnectionRepository supplierConnectionRepository,
      PullTaskPublisher pullTaskPublisher) {
    return new SupplierPullSchedulingApplicationService(
        supplierConnectionRepository,
        pullTaskPublisher);
  }
}
