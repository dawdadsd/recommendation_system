package xiaowu.example.supplieretl.application.port;

import java.time.Instant;

/**
 * 供应商拉取失败的顶层异常，使用 sealed 层次让编译器强制所有子类型都在 permits 中声明。
 *
 * <p>
 * 下游（执行服务、熔断器）通过 {@link FailureKind} 枚举而非 instanceof 判断行为：
 * <ul>
 * <li>{@link FailureKind#AUTH_FAILURE} — 401/403，需挂起供应商连接，等人工修复</li>
 * <li>{@link FailureKind#RATE_LIMITED} — 429，使用 ERP 返回的 Retry-After
 * 计算下次重试时间</li>
 * <li>{@link FailureKind#TIMEOUT} — 超时 / 连接失败，走指数退避</li>
 * <li>{@link FailureKind#DATA_ERROR} — 响应格式异常 / 解析失败，发送到 DLQ</li>
 * <li>{@link FailureKind#UNAVAILABLE} — 5xx / 服务不可用，触发熔断器计数</li>
 * </ul>
 */
public sealed class SupplierFetchException extends RuntimeException
    permits SupplierFetchException.AuthException,
    SupplierFetchException.RateLimitedException,
    SupplierFetchException.TimeoutException,
    SupplierFetchException.DataException,
    SupplierFetchException.UnavailableException {

  /** 供应商ID，方便日志追踪。 */
  private final long supplierId;
  /** 失败种类，是执行服务做决策的主要依据。 */
  private final FailureKind kind;

  private SupplierFetchException(long supplierId, FailureKind kind, String message, Throwable cause) {
    super(message, cause);
    this.supplierId = supplierId;
    this.kind = kind;
  }

  public long supplierId() {
    return supplierId;
  }

  public FailureKind kind() {
    return kind;
  }

  // ─── 失败种类枚举 ────────────────────────────────────────────────────────────

  public enum FailureKind {
    /** 鉴权失败，需要人工修复凭证后才能重试。 */
    AUTH_FAILURE,
    /** 触发限流，应严格按照 retryAfter 字段推迟重试时间。 */
    RATE_LIMITED,
    /** 请求超时或网络中断，指数退避后重试。 */
    TIMEOUT,
    /** 响应数据格式错误 / 解析异常，该条消息进 DLQ。 */
    DATA_ERROR,
    /** 服务端 5xx / 暂时不可用，计入熔断器失败计数。 */
    UNAVAILABLE
  }

  // ─── 子类型 ──────────────────────────────────────────────────────────────────

  /**
   * 鉴权/授权失败（HTTP 401 / 403）。
   * 执行服务收到此异常后应将供应商连接状态置为 SUSPENDED。
   */
  public static final class AuthException extends SupplierFetchException {
    private final int httpStatus;

    public AuthException(long supplierId, int httpStatus) {
      super(supplierId, FailureKind.AUTH_FAILURE,
          "Auth failed for supplier=" + supplierId + " httpStatus=" + httpStatus, null);
      this.httpStatus = httpStatus;
    }

    public int httpStatus() {
      return httpStatus;
    }
  }

  /**
   * 限流（HTTP 429）。
   * {@code retryAfter} 来自响应头 {@code Retry-After}，为 null 时降级到指数退避。
   */
  public static final class RateLimitedException extends SupplierFetchException {
    /** ERP 要求的最早重试时刻，可能为 null（未提供 Retry-After 时）。 */
    private final Instant retryAfter;

    public RateLimitedException(long supplierId, Instant retryAfter) {
      super(supplierId, FailureKind.RATE_LIMITED,
          "Rate limited for supplier=" + supplierId
              + (retryAfter != null ? " retryAfter=" + retryAfter : ""),
          null);
      this.retryAfter = retryAfter;
    }

    public Instant retryAfter() {
      return retryAfter;
    }
  }

  /**
   * 请求超时 / 连接超时 / 网络中断。
   */
  public static final class TimeoutException extends SupplierFetchException {
    public TimeoutException(long supplierId, String message, Throwable cause) {
      super(supplierId, FailureKind.TIMEOUT, message, cause);
    }
  }

  /**
   * 响应解析失败 / 格式异常。
   * 该批次原始数据将被写入 supplier.raw.dlq 主题。
   */
  public static final class DataException extends SupplierFetchException {
    private final String rawSnippet;

    public DataException(long supplierId, String rawSnippet, Throwable cause) {
      super(supplierId, FailureKind.DATA_ERROR,
          "Data parse error for supplier=" + supplierId, cause);
      this.rawSnippet = rawSnippet;
    }

    /** 原始响应片段（最多 512 字节），供 DLQ 记录使用。 */
    public String rawSnippet() {
      return rawSnippet;
    }
  }

  /**
   * 服务端 5xx 或临时不可用，计入熔断器。
   */
  public static final class UnavailableException extends SupplierFetchException {
    private final int httpStatus;

    public UnavailableException(long supplierId, int httpStatus, String message) {
      super(supplierId, FailureKind.UNAVAILABLE, message, null);
      this.httpStatus = httpStatus;
    }

    public int httpStatus() {
      return httpStatus;
    }
  }
}
