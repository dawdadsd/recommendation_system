package xiaowu.example.supplieretl.application.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import xiaowu.example.supplieretl.application.etl.model.NormalizedSupplierRecord;
import xiaowu.example.supplieretl.application.etl.parser.SupplierRawPayloadParserDispatcher;
import xiaowu.example.supplieretl.application.port.NormalizedSupplierRecordRepository;
import xiaowu.example.supplieretl.application.port.NormalizedSupplierRecordRepository.UpsertSummary;
import xiaowu.example.supplieretl.application.port.RawDataPublisher;
import xiaowu.example.supplieretl.application.port.RawDataPublisher.DlqEvent;
import xiaowu.example.supplieretl.application.port.RawDataPublisher.RawDataEvent;

/**
 * 消费 supplier.raw.data 事件，完成解析和标准化落库。
 */
@Slf4j
public class SupplierRawDataProcessingApplicationService {

  private static final int MAX_DLQ_SNIPPET_LENGTH = 512;
  private static final String TRANSFORM_ERROR = "TRANSFORM_ERROR";

  private final SupplierRawPayloadParserDispatcher parserDispatcher;
  private final NormalizedSupplierRecordRepository normalizedSupplierRecordRepository;
  private final RawDataPublisher rawDataPublisher;

  public SupplierRawDataProcessingApplicationService(
      SupplierRawPayloadParserDispatcher parserDispatcher,
      NormalizedSupplierRecordRepository normalizedSupplierRecordRepository,
      RawDataPublisher rawDataPublisher) {
    this.parserDispatcher = Objects.requireNonNull(parserDispatcher, "parserDispatcher");
    this.normalizedSupplierRecordRepository = Objects.requireNonNull(
        normalizedSupplierRecordRepository, "normalizedSupplierRecordRepository");
    this.rawDataPublisher = Objects.requireNonNull(rawDataPublisher, "rawDataPublisher");
  }

  @Transactional
  public ProcessingResult process(RawDataEvent event) {
    Objects.requireNonNull(event, "event");

    List<NormalizedSupplierRecord> records;
    try {
      records = parserDispatcher.parse(event);
    } catch (RuntimeException ex) {
      return handleTransformFailure(event, ex);
    }

    if (records.isEmpty()) {
      log.info("[RawData] supplierId={} parsed 0 records, nothing to load", event.supplierId());
      return new ProcessingResult(event.supplierId(), true, 0, 0, 0, null);
    }

    UpsertSummary summary = normalizedSupplierRecordRepository.upsertAll(records);
    log.info(
        "[RawData] supplierId={} parsed={} upserted={} staleSkipped={}",
        event.supplierId(),
        records.size(),
        summary.upsertedCount(),
        summary.staleSkippedCount());

    return new ProcessingResult(
        event.supplierId(),
        true,
        records.size(),
        summary.upsertedCount(),
        summary.staleSkippedCount(),
        null);
  }

  /**
   * 处理解析或转换失败的情况，把事件和错误信息发送到 DLQ 以供后续分析。
   *
   * @param event 原始事件
   * @param ex    发生的异常
   * @return 处理结果，success=false，包含错误信息摘要
   */
  private ProcessingResult handleTransformFailure(RawDataEvent event, RuntimeException ex) {
    rawDataPublisher.publishDlq(new DlqEvent(
        event.supplierId(),
        event.supplierCode(),
        event.erpType(),
        abbreviate(event.rawPayload(), MAX_DLQ_SNIPPET_LENGTH),
        TRANSFORM_ERROR,
        abbreviate(ex.getMessage(), MAX_DLQ_SNIPPET_LENGTH),
        Instant.now()));

    log.warn(
        "[RawData] supplierId={} transform failed, event moved to DLQ",
        event.supplierId(),
        ex);

    return new ProcessingResult(
        event.supplierId(),
        false,
        0,
        0,
        0,
        ex.getMessage());
  }

  /**
   * 截断字符串以适应 DLQ 存储限制，避免过长的原始负载或错误信息导致存储失败。
   *
   * @param value     要截断的字符串
   * @param maxLength 最大长度限制
   * @return 截断后的字符串，如果原字符串长度不超过限制，则返回原字符串
   */
  private static String abbreviate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  public record ProcessingResult(
      long supplierId,
      boolean success,
      int parsedCount,
      int upsertedCount,
      int staleSkippedCount,
      String errorMessage) {
  }
}
