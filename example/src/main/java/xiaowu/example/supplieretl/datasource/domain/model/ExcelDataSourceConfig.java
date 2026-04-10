package xiaowu.example.supplieretl.datasource.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Excel数据源配置类，包含连接Excel所需的基本信息
 *
 * @param sheetName      可选的工作表名称，指定要连接的Excel工作表，如果为null或空字符串，则默认连接第一个工作表
 * @param headerRowIndex 表头行索引，指定Excel表格中表头所在的行，索引从0开始，默认为0，表示第一行是表头
 * @param sampleSize     样本数据行数，指定在连接Excel时要读取
 */
public record ExcelDataSourceConfig(
    String sheetName,
    Integer headerRowIndex,
    Integer sampleSize) implements DataSourceConfig {

  public ExcelDataSourceConfig {
    sheetName = DataSourceConfigSupport.normalizeNullableText(sheetName);
    headerRowIndex = DataSourceConfigSupport.requireNonNegative(headerRowIndex, 0, "headerRowIndex");
    sampleSize = DataSourceConfigSupport.requirePositive(sampleSize, 20, "sampleSize");
  }

  @Override
  public void validate() {
    // Validation happens in the canonical constructor.
  }

  @Override
  public Map<String, Object> toMaskedMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    if (sheetName != null) {
      values.put("sheetName", sheetName);
    }
    values.put("headerRowIndex", headerRowIndex);
    values.put("sampleSize", sampleSize);
    return values;
  }
}
