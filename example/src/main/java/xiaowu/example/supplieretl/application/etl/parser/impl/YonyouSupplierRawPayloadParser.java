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
public class YonyouSupplierRawPayloadParser implements SupplierRawPayloadParser {

  private static final String ERP_TYPE = "YONYOU";

  private final ObjectMapper objectMapper;
  private final SupplierStatusNormalizer supplierStatusNormalizer;

  public YonyouSupplierRawPayloadParser(
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
            "YONYOU row must be object, rowIndex=" + rowIndex);
      }

      records.add(toRecord(event, rowNode, rowIndex));
    }

    return List.copyOf(records);
  }

  private ArrayNode readRowsArray(RawDataEvent event) {
    try {
      JsonNode root = objectMapper.readTree(event.rawPayload());
      if (!root.isObject()) {
        throw new IllegalArgumentException("YONYOU root must be JSON object");
      }

      int code = root.path("code").asInt(-1);
      if (code != 0) {
        throw new IllegalArgumentException("YONYOU response code is not success: " + code);
      }

      JsonNode dataNode = root.get("data");
      if (dataNode == null || !dataNode.isObject()) {
        throw new IllegalArgumentException("YONYOU data node is missing or invalid");
      }

      JsonNode rowsNode = dataNode.get("rows");
      if (rowsNode == null || !rowsNode.isArray()) {
        throw new IllegalArgumentException("YONYOU rows node is missing or invalid");
      }

      return (ArrayNode) rowsNode;
    } catch (IOException ex) {
      throw new RuntimeException("Failed to parse YONYOU raw payload JSON", ex);
    }
  }

  private NormalizedSupplierRecord toRecord(
      RawDataEvent event,
      JsonNode row,
      int rowIndex) {

    String code = requireText(row.get("code"), "code", rowIndex);
    String supplierName = requireText(row.get("name"), "name", rowIndex);
    String sourceSupplierStatus = requireText(row.get("status"), "status", rowIndex);
    CanonicalSupplierStatus supplierStatus = supplierStatusNormalizer.normalize(
        event.erpType(),
        sourceSupplierStatus);
    String taxNo = normalizeNullableText(asNullableText(row.get("taxNo")));
    String sourceModifiedAt = normalizeNullableText(asNullableText(row.get("modifyDate")));
    String rawItemJson = writeRowJson(row, rowIndex);

    return new NormalizedSupplierRecord(
        event.supplierId(),
        event.supplierCode(),
        event.erpType(),
        code,
        code,
        supplierName,
        sourceSupplierStatus,
        supplierStatus,
        taxNo,
        sourceModifiedAt,
        normalizeNullableText(event.pageToken()),
        normalizeNullableText(event.nextPageToken()),
        event.pulledAt(),
        rawItemJson);
  }

  private String requireText(JsonNode node, String fieldName, int rowIndex) {
    if (node == null || node.isNull()) {
      throw new IllegalArgumentException(
          "YONYOU field is missing: " + fieldName + ", rowIndex=" + rowIndex);
    }

    String value = node.asText();
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "YONYOU field is blank: " + fieldName + ", rowIndex=" + rowIndex);
    }

    return value;
  }

  private String asNullableText(JsonNode node) {
    if (node == null || node.isNull()) {
      return "";
    }
    return node.asText();
  }

  private String writeRowJson(JsonNode row, int rowIndex) {
    try {
      return objectMapper.writeValueAsString(row);
    } catch (IOException ex) {
      throw new IllegalArgumentException(
          "Failed to serialize YONYOU row, rowIndex=" + rowIndex, ex);
    }
  }

  private String normalizeNullableText(String value) {
    return value == null ? "" : value.trim();
  }
}
