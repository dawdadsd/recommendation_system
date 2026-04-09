package xiaowu.example.supplieretl.application.port;

import java.util.List;

import xiaowu.example.supplieretl.application.etl.model.NormalizedSupplierRecord;

/**
 * 标准化供应商记录的落库端口。
 *
 * <p>上游 parser 负责把不同 ERP 的原始 payload 转成统一模型，
 * 这个端口负责把统一模型以幂等方式写入标准化结果表。
 */
public interface NormalizedSupplierRecordRepository {

  UpsertSummary upsertAll(List<NormalizedSupplierRecord> records);

  record UpsertSummary(int upsertedCount, int staleSkippedCount) {
  }
}
