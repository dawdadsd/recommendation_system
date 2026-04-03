package xiaowu.example.supplieretl.application.port;

import java.time.Instant;

/**
 * 原始数据发布端口。
 *
 * <p>
 * 执行服务在每次成功从 ERP 拉取数据后调用此接口，
 * 将未经任何转换的原始响应体投入 Kafka {@code supplier.raw.data} 主题，
 * 供下游 Spark ETL 流程做标准化、清洗、聚合使用。
 *
 * <p>
 * 失败场景（解析异常、序列化异常）则调用 {@link #publishDlq} 写入死信队列。
 */
public interface RawDataPublisher {

  /**
   * 发布一条原始数据事件到 {@code supplier.raw.data} 主题。
   *
   * @param event 原始拉取事件，不得为 null
   */
  void publish(RawDataEvent event);

  /**
   * 将无法正常处理的原始消息写入 DLQ（{@code supplier.raw.dlq}）。
   *
   * @param event 死信事件，不得为 null
   */
  void publishDlq(DlqEvent event);

  /**
   * 原始数据事件，直接承载来自 ERP 的响应 JSON 字符串。
   *
   * @param supplierId    供应商 ID
   * @param supplierCode  供应商编码，决定下游 ETL 的解析策略（KD_ → 金蝶，YY_ → 用友）
   * @param erpType       ERP 系统类型标签：{@code KINGDEE} / {@code YONYOU} /
   *                      {@code GENERIC}
   * @param rawPayload    从 ERP 接口拿到的原始 JSON 字符串，不做任何字段修改
   * @param pageToken     当前页游标，用于 Spark 侧做幂等去重
   * @param nextPageToken 下一页游标，Spark 处理时可忽略（由调度侧管理）
   * @param pulledAt      拉取时间戳，以秒级 epoch 表示
   * @param recordCount   本次响应中的记录条数
   */
  record RawDataEvent(
      long supplierId,
      String supplierCode,
      String erpType,
      String rawPayload,
      String pageToken,
      String nextPageToken,
      Instant pulledAt,
      int recordCount) {
  }

  /**
   * 死信事件（写入 {@code supplier.raw.dlq}）。
   *
   * @param supplierId   供应商 ID
   * @param supplierCode 供应商编码
   * @param erpType      ERP 类型
   * @param rawSnippet   原始响应片段（最多 512 字符，防止超大消息撑爆 Kafka）
   * @param errorType    失败分类，来自 {@link SupplierFetchException.FailureKind#name()}
   * @param errorMessage 异常消息
   * @param failedAt     失败时间戳
   */
  record DlqEvent(
      long supplierId,
      String supplierCode,
      String erpType,
      String rawSnippet,
      String errorType,
      String errorMessage,
      Instant failedAt) {
  }
}
