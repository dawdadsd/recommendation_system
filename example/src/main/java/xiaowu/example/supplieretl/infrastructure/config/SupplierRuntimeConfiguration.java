package xiaowu.example.supplieretl.infrastructure.config;

import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.supplieretl.application.port.RawDataPublisher;
import xiaowu.example.supplieretl.application.port.SupplierPullClient;
import xiaowu.example.supplieretl.application.service.SupplierPullExecutionApplicationService;
import xiaowu.example.supplieretl.domain.repository.SupplierConnectionRepository;
import xiaowu.example.supplieretl.infrastructure.adapter.RoutingSupplierPullClient;
import xiaowu.example.supplieretl.infrastructure.adapter.kingdee.KingdeeErpAdapter;
import xiaowu.example.supplieretl.infrastructure.adapter.kingdee.KingdeeErpProperties;
import xiaowu.example.supplieretl.infrastructure.adapter.yonyou.YonyouErpAdapter;
import xiaowu.example.supplieretl.infrastructure.adapter.yonyou.YonyouErpProperties;
import xiaowu.example.supplieretl.infrastructure.audit.SupplierPullAuditRepository;
import xiaowu.example.supplieretl.infrastructure.kafka.SupplierPullTaskConsumer;
import xiaowu.example.supplieretl.infrastructure.mock.MockSupplierPullClient;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({
    SupplierSchedulingProperties.class,
    SupplierWorkerProperties.class,
    KingdeeErpProperties.class,
    YonyouErpProperties.class
})
public class SupplierRuntimeConfiguration {

  // ── ERP 适配器 ────────────────────────────────────────────────────────────────

  @Bean
  KingdeeErpAdapter kingdeeErpAdapter(KingdeeErpProperties props, ObjectMapper objectMapper) {
    return new KingdeeErpAdapter(props, objectMapper);
  }

  @Bean
  YonyouErpAdapter yonyouErpAdapter(YonyouErpProperties props, ObjectMapper objectMapper) {
    return new YonyouErpAdapter(props, objectMapper);
  }

  /**
   * 路由客户端：KD_ → 金蝶，YY_ → 用友，其余 → Mock 兜底。
   *
   * <p>
   * 使用 {@code @Primary} 让 {@link SupplierPullTaskConsumer} 注入路由版本，
   * 而不是原先的 {@link MockSupplierPullClient}。
   */
  @Bean
  @Primary
  SupplierPullClient routingSupplierPullClient(
      KingdeeErpAdapter kingdeeErpAdapter,
      YonyouErpAdapter yonyouErpAdapter,
      MockSupplierPullClient mockClient) {
    // 熔断器默认参数：5 次连续失败后打开，60 秒后进入 HALF_OPEN 探针
    Map<String, Object> cbConfig = Map.of(
        "failureThreshold", 5,
        "openWindowMs", 60_000L);
    return new RoutingSupplierPullClient(
        kingdeeErpAdapter, yonyouErpAdapter, mockClient, cbConfig);
  }

  // ── 执行应用服务（注入全部依赖）─────────────────────────────────────────────────

  @Bean
  SupplierPullExecutionApplicationService supplierPullExecutionApplicationService(
      SupplierConnectionRepository supplierConnectionRepository,
      SupplierPullClient routingSupplierPullClient,
      RawDataPublisher rawDataPublisher,
      SupplierPullAuditRepository auditRepository) {
    return new SupplierPullExecutionApplicationService(
        supplierConnectionRepository,
        routingSupplierPullClient,
        rawDataPublisher,
        auditRepository);
  }
}
