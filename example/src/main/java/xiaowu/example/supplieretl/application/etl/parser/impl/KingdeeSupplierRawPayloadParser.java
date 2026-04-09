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

@Component
public class KingdeeSupplierRawPayloadParser implements SupplierRawPayloadParser {
  private static final String ERP_TYPE = "KINGDEE";

  private final ObjectMapper objectMapper;
  private final SupplierStatusNormalizer supplierStatusNormalizer;

  public KingdeeSupplierRawPayloadParser(
      ObjectMapper objectMapper,
      SupplierStatusNormalizer supplierStatusNormalizer) {
    this.objectMapper = objectMapper;
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
      // TODO : 后续新增特定的业务异常类型
      throw new IllegalArgumentException("Unsupported ERP type: " + event.erpType());
    }
    ArrayNode rows = readRootArray(event);
    List<NormalizedSupplierRecord> records = new ArrayList<>(rows.size());
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      JsonNode rowNode = rows.get(rowIndex);
      if (rowNode == null || !rowNode.isArray()) {
        // TODO : 后续会做坏数据的处理+审计，目前为了保证示例代码简洁，直接抛异常
        throw new IllegalArgumentException("Invalid row: " + rowNode);
      }
      records.add(toRecord(event, (ArrayNode) rowNode, rowIndex));
    }
    return List.copyOf(records);
  }

  private ArrayNode readRootArray(RawDataEvent event) {
    try {
      JsonNode root = objectMapper.readTree(event.rawPayload());
      if (!root.isArray()) {
        // TODO : 后续新增特定的业务异常类型
        throw new IllegalArgumentException("Expected JSON array as root");
      }
      return (ArrayNode) root;
    } catch (IOException ex) {
      // TODO : 后续新增特定的业务异常类型
      throw new RuntimeException("Failed to parse raw payload JSON", ex);
    }
  }

  private NormalizedSupplierRecord toRecord(
      RawDataEvent event,
      ArrayNode row,
      int rowIndex) {
    String sourceRecordId = requireText(row.get(0), "sourceRecordId", rowIndex);
    String supplierName = requireText(row.get(1), "supplierName", rowIndex);
    String sourceBusinessCode = requireText(row.get(2), "sourceBusinessCode", rowIndex);
    String sourceSupplierStatus = requireText(row.get(3), "supplierStatus", rowIndex);
    CanonicalSupplierStatus supplierStatus = supplierStatusNormalizer.normalize(
        event.erpType(),
        sourceSupplierStatus);
    String sourceModifiedAt = requireText(row.get(4), "sourceModifiedAt", rowIndex);
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
        "",
        sourceModifiedAt,
        normalizeNullableText(event.pageToken()),
        normalizeNullableText(event.nextPageToken()),
        event.pulledAt(),
        rawItemJson);
  }

  private String requireText(JsonNode node, String fieldName, int rowIndex) {
    if (node == null || node.isNull()) {
      throw new IllegalArgumentException(
          "KINGDEE field is missing: " + fieldName + ", rowIndex=" + rowIndex);
    }

    String value = node.asText();
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "KINGDEE field is blank: " + fieldName + ", rowIndex=" + rowIndex);
    }

    return value;
  }

  private String writeRowJson(JsonNode row, int rowIndex) {
    try {
      return objectMapper.writeValueAsString(row);
    } catch (IOException ex) {
      throw new IllegalArgumentException(
          "Failed to serialize KINGDEE row, rowIndex=" + rowIndex, ex);
    }
  }

  private String normalizeNullableText(String value) {
    return value == null ? "" : value.trim();
  }

}
