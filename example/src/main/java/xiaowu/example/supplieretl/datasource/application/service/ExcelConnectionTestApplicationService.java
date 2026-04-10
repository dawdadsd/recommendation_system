package xiaowu.example.supplieretl.datasource.application.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import xiaowu.example.supplieretl.datasource.application.model.ConnectionTestResult;
import xiaowu.example.supplieretl.datasource.domain.model.ExcelDataSourceConfig;

@Service
public class ExcelConnectionTestApplicationService {

  public ConnectionTestResult test(MultipartFile file, ExcelDataSourceConfig config) {
    Objects.requireNonNull(file, "file must not be null");
    Objects.requireNonNull(config, "config must not be null");

    if (file.isEmpty()) {
      throw new IllegalArgumentException("Excel file must not be empty");
    }
    String filename = file.getOriginalFilename();
    if (filename == null || !filename.toLowerCase().endsWith(".xlsx")) {
      throw new IllegalArgumentException("Only .xlsx files are supported");
    }

    try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
      List<String> sheetNames = new ArrayList<>();
      for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
        sheetNames.add(workbook.getSheetAt(i).getSheetName());
      }

      Sheet sheet = selectSheet(workbook, config.sheetName());
      List<String> headers = readRow(sheet.getRow(config.headerRowIndex()));
      List<List<String>> sampleRows = readSampleRows(sheet, config.headerRowIndex() + 1, config.sampleSize());

      return ConnectionTestResult.success(
          "Excel parsing test passed",
          Map.of(
              "fileName", filename,
              "sheetNames", sheetNames,
              "selectedSheet", sheet.getSheetName(),
              "headerRowIndex", config.headerRowIndex(),
              "headers", headers,
              "sampleRows", sampleRows));
    } catch (IOException ex) {
      return ConnectionTestResult.failure(
          "Excel parsing test failed: " + ex.getMessage(),
          Map.of(
              "fileName", filename == null ? "" : filename,
              "errorType", ex.getClass().getSimpleName()));
    }
  }

  private static Sheet selectSheet(Workbook workbook, String sheetName) {
    if (sheetName == null) {
      return workbook.getSheetAt(0);
    }
    Sheet sheet = workbook.getSheet(sheetName);
    if (sheet == null) {
      throw new IllegalArgumentException("Sheet not found: " + sheetName);
    }
    return sheet;
  }

  private static List<String> readRow(Row row) {
    if (row == null) {
      return List.of();
    }
    DataFormatter formatter = new DataFormatter();
    List<String> values = new ArrayList<>();
    for (int i = 0; i < row.getLastCellNum(); i++) {
      Cell cell = row.getCell(i);
      values.add(cell == null ? "" : formatter.formatCellValue(cell));
    }
    return List.copyOf(values);
  }

  private static List<List<String>> readSampleRows(Sheet sheet, int startRowIndex, int sampleSize) {
    List<List<String>> rows = new ArrayList<>();
    int maxRowIndex = sheet.getLastRowNum();
    for (int rowIndex = startRowIndex; rowIndex <= maxRowIndex && rows.size() < sampleSize; rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        continue;
      }
      rows.add(readRow(row));
    }
    return List.copyOf(rows);
  }
}
