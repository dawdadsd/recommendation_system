package xiaowu.example.supplieretl.application.etl.parser;

import java.util.List;

import xiaowu.example.supplieretl.application.etl.model.NormalizedSupplierRecord;
import xiaowu.example.supplieretl.application.port.RawDataPublisher.RawDataEvent;

/**
 * ERP 原始负载解析器。
 *
 * <p>
 * 负责把 Kafka 中的 RawDataEvent 解析成统一的标准化记录列表。
 * 不同 ERP（如 KINGDEE / YONYOU）会有各自的实现类。
 */
public interface SupplierRawPayloadParser {

  /**
   * 当前解析器是否支持该 ERP 类型。
   *
   * @param erpType ERP 类型，如 KINGDEE / YONYOU / GENERIC
   * @return 支持返回 true，否则返回 false
   */
  boolean supports(String erpType);

  /**
   * 将一条原始 Kafka 事件解析成标准化记录列表。
   *
   * <p>
   * 一条 RawDataEvent 可能对应 0~N 条业务记录，
   * 所以这里返回的是 List，而不是单条对象。
   *
   * @param event Kafka 原始事件
   * @return 标准化后的供应商记录列表
   */
  List<NormalizedSupplierRecord> parse(RawDataEvent event);
}
