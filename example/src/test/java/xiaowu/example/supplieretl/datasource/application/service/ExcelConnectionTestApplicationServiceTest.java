package xiaowu.example.supplieretl.datasource.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import xiaowu.example.supplieretl.datasource.application.model.ConnectionTestResult;
import xiaowu.example.supplieretl.datasource.domain.model.ExcelDataSourceConfig;

class ExcelConnectionTestApplicationServiceTest {

  private final ExcelConnectionTestApplicationService applicationService = new ExcelConnectionTestApplicationService();

  @Test
  void testShouldParseWorkbookAndReturnHeadersAndSamples() throws Exception {
    byte[] workbookBytes = createWorkbook();
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "supplier.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        workbookBytes);

    ConnectionTestResult result = applicationService.test(
        file,
        new ExcelDataSourceConfig("suppliers", 0, 5));

    assertThat(result.success()).isTrue();
    assertThat(result.detail()).containsEntry("selectedSheet", "suppliers");
    assertThat(result.detail().get("headers")).asList().containsExactly("id", "name", "status");
  }

  private byte[] createWorkbook() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
         ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("suppliers");
      var header = sheet.createRow(0);
      header.createCell(0).setCellValue("id");
      header.createCell(1).setCellValue("name");
      header.createCell(2).setCellValue("status");

      var row1 = sheet.createRow(1);
      row1.createCell(0).setCellValue("1001");
      row1.createCell(1).setCellValue("ACME");
      row1.createCell(2).setCellValue("ACTIVE");

      workbook.write(outputStream);
      return outputStream.toByteArray();
    }
  }
}
