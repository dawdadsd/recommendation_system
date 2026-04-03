package xiaowu.example.supplieretl.infrastructure.loadtest;

import java.util.concurrent.atomic.LongAdder;

import lombok.extern.slf4j.Slf4j;
import xiaowu.example.supplieretl.application.port.RawDataPublisher;

/**
 * 空实现的 RawDataPublisher，仅统计调用次数，不发送任何 Kafka 消息。
 *
 * <p>
 * 用于无 Kafka 环境下的本地压测演示（supplier.load-test.mock-publisher=true）。
 * 生产接入真实 Kafka 时将该属性改为 false 即可。
 *
 * <p>
 * {@link LongAdder} 比 {@code AtomicLong} 在高并发下竞争更小，
 * 适合万级并发 increment 场景。
 */
@Slf4j
public class NoOpRawDataPublisher implements RawDataPublisher {

  private final LongAdder publishCount = new LongAdder();
  private final LongAdder dlqCount = new LongAdder();

  @Override
  public void publish(RawDataEvent event) {
    publishCount.increment();
    log.trace("[NoOp] raw-data supplierId={} records={}", event.supplierId(), event.recordCount());
  }

  @Override
  public void publishDlq(DlqEvent event) {
    dlqCount.increment();
    log.trace("[NoOp] dlq supplierId={} error={}", event.supplierId(), event.errorType());
  }

  /** 压测期间累计发布的原始数据条数。 */
  public long getPublishCount() {
    return publishCount.sum();
  }

  /** 压测期间累计发送到 DLQ 的条数。 */
  public long getDlqCount() {
    return dlqCount.sum();
  }
}
