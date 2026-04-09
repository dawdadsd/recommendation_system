package xiaowu.example.supplieretl.application.etl.transform;

import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Component;

import xiaowu.example.supplieretl.application.etl.model.CanonicalSupplierStatus;

/**
 * 把不同 ERP 的原始状态码统一成平台内部状态。
 */
@Component
public class SupplierStatusNormalizer {

  public CanonicalSupplierStatus normalize(String erpType, String sourceStatus) {
    Objects.requireNonNull(erpType, "erpType");
    Objects.requireNonNull(sourceStatus, "sourceStatus");

    String normalizedErpType = erpType.trim().toUpperCase(Locale.ROOT);
    String normalizedSourceStatus = sourceStatus.trim().toUpperCase(Locale.ROOT);

    return switch (normalizedErpType) {
      case "KINGDEE" -> normalizeKingdee(normalizedSourceStatus);
      case "YONYOU" -> normalizeYonyou(normalizedSourceStatus);
      case "GENERIC" -> normalizeGeneric(normalizedSourceStatus);
      default -> CanonicalSupplierStatus.UNKNOWN;
    };
  }

  private CanonicalSupplierStatus normalizeKingdee(String sourceStatus) {
    return switch (sourceStatus) {
      case "A", "ENABLE", "ENABLED", "ACTIVE" -> CanonicalSupplierStatus.ACTIVE;
      case "B", "DISABLE", "DISABLED", "INACTIVE" -> CanonicalSupplierStatus.INACTIVE;
      default -> CanonicalSupplierStatus.UNKNOWN;
    };
  }

  private CanonicalSupplierStatus normalizeYonyou(String sourceStatus) {
    return switch (sourceStatus) {
      case "Y", "YES", "ENABLE", "ENABLED", "ACTIVE", "1" -> CanonicalSupplierStatus.ACTIVE;
      case "N", "NO", "DISABLE", "DISABLED", "INACTIVE", "0" -> CanonicalSupplierStatus.INACTIVE;
      default -> CanonicalSupplierStatus.UNKNOWN;
    };
  }

  private CanonicalSupplierStatus normalizeGeneric(String sourceStatus) {
    return switch (sourceStatus) {
      case "ACTIVE", "ENABLE", "ENABLED", "VALID" -> CanonicalSupplierStatus.ACTIVE;
      case "INACTIVE", "DISABLE", "DISABLED", "INVALID" -> CanonicalSupplierStatus.INACTIVE;
      default -> CanonicalSupplierStatus.UNKNOWN;
    };
  }
}
