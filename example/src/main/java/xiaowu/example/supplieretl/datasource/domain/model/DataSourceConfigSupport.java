package xiaowu.example.supplieretl.datasource.domain.model;

/**
 * 数据源配置支持类
 */
final class DataSourceConfigSupport {

  private DataSourceConfigSupport() {
  }

  static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value.trim();
  }

  static String normalizeNullableText(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  static int requirePositive(Integer value, int defaultValue, String fieldName) {
    int resolved = value == null ? defaultValue : value;
    if (resolved <= 0) {
      throw new IllegalArgumentException(fieldName + " must be positive");
    }
    return resolved;
  }

  static int requireNonNegative(Integer value, int defaultValue, String fieldName) {
    int resolved = value == null ? defaultValue : value;
    if (resolved < 0) {
      throw new IllegalArgumentException(fieldName + " must not be negative");
    }
    return resolved;
  }

  static String defaultIfBlank(String value, String defaultValue) {
    String normalized = normalizeNullableText(value);
    return normalized == null ? defaultValue : normalized;
  }

  static String maskSecret(String secret) {
    if (secret == null || secret.isBlank()) {
      return null;
    }
    return "******";
  }
}
