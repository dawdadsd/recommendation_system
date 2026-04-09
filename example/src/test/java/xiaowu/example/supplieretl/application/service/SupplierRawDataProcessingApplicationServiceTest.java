package xiaowu.example.supplieretl.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.supplieretl.application.etl.model.CanonicalSupplierStatus;
import xiaowu.example.supplieretl.application.etl.model.NormalizedSupplierRecord;
import xiaowu.example.supplieretl.application.etl.parser.SupplierRawPayloadParserDispatcher;
import xiaowu.example.supplieretl.application.etl.parser.impl.GenericSupplierRawPayloadParser;
import xiaowu.example.supplieretl.application.etl.parser.impl.KingdeeSupplierRawPayloadParser;
import xiaowu.example.supplieretl.application.etl.parser.impl.YonyouSupplierRawPayloadParser;
import xiaowu.example.supplieretl.application.etl.transform.SupplierStatusNormalizer;
import xiaowu.example.supplieretl.application.port.NormalizedSupplierRecordRepository;
import xiaowu.example.supplieretl.application.port.NormalizedSupplierRecordRepository.UpsertSummary;
import xiaowu.example.supplieretl.application.port.RawDataPublisher;
import xiaowu.example.supplieretl.application.port.RawDataPublisher.DlqEvent;
import xiaowu.example.supplieretl.application.port.RawDataPublisher.RawDataEvent;

@ExtendWith(MockitoExtension.class)
class SupplierRawDataProcessingApplicationServiceTest {

  @Mock
  private NormalizedSupplierRecordRepository normalizedSupplierRecordRepository;

  @Mock
  private RawDataPublisher rawDataPublisher;

  private SupplierRawDataProcessingApplicationService applicationService;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    SupplierStatusNormalizer supplierStatusNormalizer = new SupplierStatusNormalizer();
    SupplierRawPayloadParserDispatcher dispatcher = new SupplierRawPayloadParserDispatcher(List.of(
        new GenericSupplierRawPayloadParser(objectMapper, supplierStatusNormalizer),
        new KingdeeSupplierRawPayloadParser(objectMapper, supplierStatusNormalizer),
        new YonyouSupplierRawPayloadParser(objectMapper, supplierStatusNormalizer)));
    applicationService = new SupplierRawDataProcessingApplicationService(
        dispatcher,
        normalizedSupplierRecordRepository,
        rawDataPublisher);
  }

  @Test
  void processShouldParseKingdeePayloadAndPersistNormalizedRecords() {
    RawDataEvent event = new RawDataEvent(
        9101L,
        "KD_SUPPLIER_HANGZHOU",
        "KINGDEE",
        """
            [
              ["FID-1001", "Hangzhou Supplier", "SUP-1001", "ENABLE", "2026-04-09 09:30:00"]
            ]
            """,
        "cursor-1",
        "cursor-2",
        Instant.parse("2026-04-09T01:30:00Z"),
        1);

    when(normalizedSupplierRecordRepository.upsertAll(any()))
        .thenReturn(new UpsertSummary(1, 0));

    var result = applicationService.process(event);

    assertThat(result.success()).isTrue();
    assertThat(result.parsedCount()).isEqualTo(1);
    assertThat(result.upsertedCount()).isEqualTo(1);
    assertThat(result.staleSkippedCount()).isZero();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<NormalizedSupplierRecord>> recordsCaptor = ArgumentCaptor.forClass(List.class);
    verify(normalizedSupplierRecordRepository).upsertAll(recordsCaptor.capture());
    verify(rawDataPublisher, never()).publishDlq(any());

    List<NormalizedSupplierRecord> records = recordsCaptor.getValue();
    assertThat(records).hasSize(1);
    assertThat(records.get(0).supplierId()).isEqualTo(9101L);
    assertThat(records.get(0).sourceRecordId()).isEqualTo("FID-1001");
    assertThat(records.get(0).sourceBusinessCode()).isEqualTo("SUP-1001");
    assertThat(records.get(0).supplierName()).isEqualTo("Hangzhou Supplier");
    assertThat(records.get(0).sourceSupplierStatus()).isEqualTo("ENABLE");
    assertThat(records.get(0).supplierStatus()).isEqualTo(CanonicalSupplierStatus.ACTIVE);
  }

  @Test
  void processShouldPublishDlqWhenTransformFails() {
    RawDataEvent event = new RawDataEvent(
        9201L,
        "YY_SUPPLIER_BEIJING",
        "YONYOU",
        "{\"code\":500,\"message\":\"mock error\"}",
        "page-1",
        "page-2",
        Instant.parse("2026-04-09T02:00:00Z"),
        0);

    var result = applicationService.process(event);

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("not success");
    verify(normalizedSupplierRecordRepository, never()).upsertAll(any());

    ArgumentCaptor<DlqEvent> dlqCaptor = ArgumentCaptor.forClass(DlqEvent.class);
    verify(rawDataPublisher).publishDlq(dlqCaptor.capture());
    assertThat(dlqCaptor.getValue().supplierId()).isEqualTo(9201L);
    assertThat(dlqCaptor.getValue().errorType()).isEqualTo("TRANSFORM_ERROR");
  }

  @Test
  void processShouldParseGenericPayloadAndPersistNormalizedRecords() {
    RawDataEvent event = new RawDataEvent(
        1_000_068L,
        "GEN_LOAD_0000068",
        "GENERIC",
        """
            {
              "supplierId": 1000068,
              "supplierCode": "GEN_LOAD_0000068",
              "records": [
                { "id": 1000068001, "name": "mock-record-1", "status": "ACTIVE" },
                { "id": 1000068002, "name": "mock-record-2", "status": "INACTIVE" }
              ]
            }
            """,
        "cursor-1",
        "cursor-2",
        Instant.parse("2026-04-09T02:10:00Z"),
        2);

    when(normalizedSupplierRecordRepository.upsertAll(any()))
        .thenReturn(new UpsertSummary(2, 0));

    var result = applicationService.process(event);

    assertThat(result.success()).isTrue();
    assertThat(result.parsedCount()).isEqualTo(2);
    assertThat(result.upsertedCount()).isEqualTo(2);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<NormalizedSupplierRecord>> recordsCaptor = ArgumentCaptor.forClass(List.class);
    verify(normalizedSupplierRecordRepository).upsertAll(recordsCaptor.capture());

    List<NormalizedSupplierRecord> records = recordsCaptor.getValue();
    assertThat(records).hasSize(2);
    assertThat(records.get(0).sourceRecordId()).isEqualTo("1000068001");
    assertThat(records.get(0).sourceBusinessCode()).isEqualTo("1000068001");
    assertThat(records.get(0).supplierName()).isEqualTo("mock-record-1");
    assertThat(records.get(0).sourceSupplierStatus()).isEqualTo("ACTIVE");
    assertThat(records.get(0).supplierStatus()).isEqualTo(CanonicalSupplierStatus.ACTIVE);
  }
}
