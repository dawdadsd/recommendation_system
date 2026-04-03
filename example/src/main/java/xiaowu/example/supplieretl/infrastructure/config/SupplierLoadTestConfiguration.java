package xiaowu.example.supplieretl.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import xiaowu.example.supplieretl.application.port.RawDataPublisher;
import xiaowu.example.supplieretl.infrastructure.kafka.SupplierKafkaConfiguration;
import xiaowu.example.supplieretl.infrastructure.loadtest.NoOpRawDataPublisher;

/**
 * 压测辅助配置。
 *
 * <h2>Publisher 选择策略</h2>
 * <ul>
 * <li><b>默认（Kafka 在线）</b>：不配置或 {@code supplier.load-test.mock-publisher=false}，
 * {@link NoOpRawDataPublisher} bean <em>不注册</em>，
 * {@link SupplierKafkaConfiguration#rawDataPublisher} 通过
 * {@code @ConditionalOnMissingBean} 自动注册真实 Kafka 发布者。
 * 沙盒 ERP 适配器仍生成合成数据，但消息会真正写入 Kafka broker。</li>
 * <li><b>纯离线压测</b>：{@code supplier.load-test.mock-publisher=true}，
 * 注册 {@link NoOpRawDataPublisher}，无需 Kafka 在线，仅计数。</li>
 * </ul>
 *
 * <p>
 * 现有项目场景：Kafka 在线，ERP 账号使用沙盒模式（sandbox: true），
 * 无需真实 1w+ 供应商 ERP 账号，合成数据通过真实 Kafka 链路流转。
 */
@Configuration
public class SupplierLoadTestConfiguration {

  /**
   * 无 Kafka 依赖的空实现 publisher，仅统计调用次数。
   *
   * <p>
   * 仅当显式配置 {@code supplier.load-test.mock-publisher=true} 时注册。
   * 默认值为 {@code false}，即不注册，使用真实 Kafka publisher。
   */
  @Bean
  @ConditionalOnProperty(name = "supplier.load-test.mock-publisher", havingValue = "true", matchIfMissing = false) // false
                                                                                                                   // =
                                                                                                                   // 默认不注册；Kafka
                                                                                                                   // publisher
                                                                                                                   // 通过
                                                                                                                   // @ConditionalOnMissingBean
                                                                                                                   // 生效
  RawDataPublisher noOpRawDataPublisher() {
    return new NoOpRawDataPublisher();
  }
}
