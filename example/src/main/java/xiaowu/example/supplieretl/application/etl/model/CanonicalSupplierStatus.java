package xiaowu.example.supplieretl.application.etl.model;

/**
 * 跨 ERP 的统一供应商状态。
 *
 * <p>
 * 这表示“业务口径”的状态，不等于源系统原始状态码。
 * ACTIVE : 表示供应商在业务上是活跃的，可以进行交易等操作。
 * INACTIVE : 表示供应商在业务上是不活跃的，可能因为停止合作、资质过期等原因，不能进行交易等操作。
 * UNKNOWN : 表示无法确定供应商状态，可能是因为源系统状态码未被识别、数据
 */
public enum CanonicalSupplierStatus {
  ACTIVE,
  INACTIVE,
  UNKNOWN
}
