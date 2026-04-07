package xiaowu.example.supplieretl.application.etl.model;

import java.time.Instant;
import java.util.Objects;

/**
 * ETL 标准化后的供应商记录。
 *
 * <p>
 * 不管原始数据来自金蝶、用友还是 mock，最终都要映射成这一种统一结构，
 * 这样后续清洗、落库、分析都只需要面向一个模型编程。
 */
public record NormalizedSupplierRecord(
    long supplierId,
    String supplierCode,
    String erpType,
    String sourceRecordId,
    String sourceBusinessCode,
    String supplierName,
    String supplierStatus,
    String taxNo,
    String sourceModifiedAt,
    String pageToken,
    String nextPageToken,
    Instant pulledAt,
    String rawItemJson) {

  public NormalizedSupplierRecord {
    if (supplierId <= 0) {
      throw new IllegalArgumentException("supplierId must be positive");
    }

    supplierCode = requireText(supplierCode, "supplierCode must not be blank");
    erpType = requireText(erpType, "erpType must not be blank");
    sourceRecordId = requireText(sourceRecordId, "sourceRecordId must not be blank");
    sourceBusinessCode = requireText(sourceBusinessCode, "sourceBusinessCode must not be blank");
    supplierName = requireText(supplierName, "supplierName must not be blank");
    supplierStatus = requireText(supplierStatus, "supplierStatus must not be blank");

    taxNo = normalizeNullableText(taxNo);
    sourceModifiedAt = normalizeNullableText(sourceModifiedAt);
    pageToken = normalizeNullableText(pageToken);
    nextPageToken = normalizeNullableText(nextPageToken);

    pulledAt = Objects.requireNonNull(pulledAt, "pulledAt must not be null");
    rawItemJson = requireText(rawItemJson, "rawItemJson must not be blank");
  }

  /**
   * 确保字符串非空
   *
   * @param value   字符串值
   * @param message 错误消息
   * @return 原字符串值（如果非空）
   */
  private static String requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  /**
   * 将可空字符串规范化为非空（null 转为 ""，并去除首尾空白），方便后续处理和存储。
   *
   * @param value 可空字符串
   * @return 规范化后的字符串
   */
  private static String normalizeNullableText(String value) {
    if (value == null) {
      return "";
    }
    return value.trim();
  }
}
