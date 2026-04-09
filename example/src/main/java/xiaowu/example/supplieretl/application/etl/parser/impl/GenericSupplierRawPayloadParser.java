package xiaowu.example.supplieretl.application.etl.parser.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import xiaowu.example.supplieretl.application.etl.model.CanonicalSupplierStatus;
import xiaowu.example.supplieretl.application.etl.model.NormalizedSupplierRecord;
import xiaowu.example.supplieretl.application.etl.parser.SupplierRawPayloadParser;
import xiaowu.example.supplieretl.application.etl.transform.SupplierStatusNormalizer;
import xiaowu.example.supplieretl.application.port.RawDataPublisher.RawDataEvent;

/**
 * 通用/演示型供应商 payload 解析器。
 *
 * <p>
 * 当前主要用于解析 MockSupplierPullClient 产生的原始结构：
 *
 * <pre>
 * {
 *   "supplierId": 1000001,
 *   "supplierCode": "GEN_LOAD_0000001",
 *   "records": [
 *     { "id": 1000001001, "name": "mock-record-1", "status": "ACTIVE" }
 *   ]
 * }
 * </pre>
 */
@Component
public class GenericSupplierRawPayloadParser implements SupplierRawPayloadParser {

  private static final String ERP_TYPE = "GENERIC";

  private final ObjectMapper objectMapper;
  private final SupplierStatusNormalizer supplierStatusNormalizer;

  public GenericSupplierRawPayloadParser(
      ObjectMapper objectMapper,
      SupplierStatusNormalizer supplierStatusNormalizer) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.supplierStatusNormalizer = Objects.requireNonNull(
        supplierStatusNormalizer,
        "supplierStatusNormalizer");
  }

  @Override
  public boolean supports(String erpType) {
    return ERP_TYPE.equalsIgnoreCase(erpType);
  }

  @Override
  public List<NormalizedSupplierRecord> parse(RawDataEvent event) {
    Objects.requireNonNull(event, "event");
    if (!supports(event.erpType())) {
      throw new IllegalArgumentException("Unsupported ERP type: " + event.erpType());
    }

    ArrayNode rows = readRowsArray(event);
    List<NormalizedSupplierRecord> records = new ArrayList<>(rows.size());

    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      JsonNode rowNode = rows.get(rowIndex);
      if (rowNode == null || !rowNode.isObject()) {
        throw new IllegalArgumentException(
            "GENERIC row must be object, rowIndex=" + rowIndex);
      }
      records.add(toRecord(event, rowNode, rowIndex));
    }

    return List.copyOf(records);
  }

  private ArrayNode readRowsArray(RawDataEvent event) {
    try {
      JsonNode root = objectMapper.readTree(event.rawPayload());
      if (!root.isObject()) {
        throw new IllegalArgumentException("GENERIC root must be JSON object");
      }

      JsonNode rowsNode = root.get("records");
      if (rowsNode == null || !rowsNode.isArray()) {
        throw new IllegalArgumentException("GENERIC records node is missing or invalid");
      }

      return (ArrayNode) rowsNode;
    } catch (IOException ex) {
      throw new RuntimeException("Failed to parse GENERIC raw payload JSON", ex);
    }
  }

  private NormalizedSupplierRecord toRecord(
      RawDataEvent event,
      JsonNode row,
      int rowIndex) {

    String sourceRecordId = firstRequiredText(row, rowIndex, "id", "recordId", "code");
    String sourceBusinessCode = firstRequiredText(row, rowIndex, "code", "id", "recordId");
    String supplierName = firstRequiredText(row, rowIndex, "name");
    String sourceSupplierStatus = firstRequiredText(row, rowIndex, "status");
    CanonicalSupplierStatus supplierStatus = supplierStatusNormalizer.normalize(
        event.erpType(),
        sourceSupplierStatus);
    String taxNo = firstOptionalText(row, "taxNo", "tax_no");
    String sourceModifiedAt = firstOptionalText(row, "modifyDate", "modifiedAt", "updatedAt");
    String rawItemJson = writeRowJson(row, rowIndex);

    return new NormalizedSupplierRecord(
        event.supplierId(),
        event.supplierCode(),
        event.erpType(),
        sourceRecordId,
        sourceBusinessCode,
        supplierName,
        sourceSupplierStatus,
        supplierStatus,
        normalizeNullableText(taxNo),
        normalizeNullableText(sourceModifiedAt),
        normalizeNullableText(event.pageToken()),
        normalizeNullableText(event.nextPageToken()),
        event.pulledAt(),
        rawItemJson);
  }

  private String firstRequiredText(JsonNode row, int rowIndex, String... fieldNames) {
    for (String fieldName : fieldNames) {
      JsonNode node = row.get(fieldName);
      if (node == null || node.isNull()) {
        continue;
      }

      String value = node.asText();
      if (value != null && !value.isBlank()) {
        return value;
      }
    }

    throw new IllegalArgumentException(
        "GENERIC field is missing or blank: " + String.join("/", fieldNames)
            + ", rowIndex=" + rowIndex);
  }

  private String firstOptionalText(JsonNode row, String... fieldNames) {
    for (String fieldName : fieldNames) {
      JsonNode node = row.get(fieldName);
      if (node == null || node.isNull()) {
        continue;
      }

      String value = node.asText();
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private String writeRowJson(JsonNode row, int rowIndex) {
    try {
      return objectMapper.writeValueAsString(row);
    } catch (IOException ex) {
      throw new IllegalArgumentException(
          "Failed to serialize GENERIC row, rowIndex=" + rowIndex, ex);
    }
  }

  private String normalizeNullableText(String value) {
    return value == null ? "" : value.trim();
  }
}
