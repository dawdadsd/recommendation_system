package xiaowu.example.supplieretl.domain.entity;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 供应商拉动调度的聚合根。
 *
 * <p>
 * 该实体对调度事实进行建模，而不是对供应商主数据进行建模。
 * 这里关键关心的是是否可以调度供应商连接，
 * 在并发下安全地租用、重试或暂停。
 */
public class SupplierConnection {

  private final Long supplierId;
  private final String supplierCode;
  private ConnectionStatus status;
  private final int pullIntervalSeconds;
  private LocalDateTime nextPullAt;
  private LocalDateTime lastSuccessAt;
  private LocalDateTime lastErrorAt;
  private String lastCursor;
  private int retryCount;
  private LocalDateTime leaseUntil;
  private long version;
  private final LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  private SupplierConnection(
      Long supplierId,
      String supplierCode,
      ConnectionStatus status,
      int pullIntervalSeconds,
      LocalDateTime nextPullAt,
      LocalDateTime lastSuccessAt,
      LocalDateTime lastErrorAt,
      String lastCursor,
      int retryCount,
      LocalDateTime leaseUntil,
      long version,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    this.supplierId = requirePositive(supplierId, "supplierId must be positive");
    this.supplierCode = requireText(supplierCode, "supplierCode must not be blank");
    this.status = Objects.requireNonNull(status, "status must not be null");
    this.pullIntervalSeconds = requirePositive(pullIntervalSeconds, "pullIntervalSeconds must be positive");
    this.nextPullAt = Objects.requireNonNull(nextPullAt, "nextPullAt must not be null");
    this.lastSuccessAt = lastSuccessAt;
    this.lastErrorAt = lastErrorAt;
    this.lastCursor = lastCursor;
    if (retryCount < 0) {
      throw new IllegalArgumentException("retryCount must not be negative");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    this.retryCount = retryCount;
    this.leaseUntil = leaseUntil;
    this.version = version;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    validateRestoredState();
  }

  /**
   * 创建一个供应商连接。
   *
   * @param supplierId          供应商ID
   * @param supplierCode        供应商编码
   * @param pullIntervalSeconds 拉取间隔秒数，必须为正整数
   * @param firstPullAt         首次拉取时间，必须不为null
   * @return 新创建的供应商连接实例，状态为ACTIVE，初始版本为0，创建和更新时间为当前时间
   */
  public static SupplierConnection create(
      Long supplierId,
      String supplierCode,
      int pullIntervalSeconds,
      LocalDateTime firstPullAt) {

    LocalDateTime now = LocalDateTime.now();
    return new SupplierConnection(
        supplierId,
        supplierCode,
        ConnectionStatus.ACTIVE,
        pullIntervalSeconds,
        Objects.requireNonNull(firstPullAt, "firstPullAt must not be null"),
        null,
        null,
        null,
        0,
        null,
        0,
        now,
        now);
  }

  /**
   * 从持久化状态恢复一个供应商连接实例。
   *
   *
   * @param supplierId          供应商ID
   * @param supplierCode        供应商编码
   * @param status              连接状态
   * @param pullIntervalSeconds 拉取间隔秒数，必须为正整数
   * @param nextPullAt          下一次允许被调度的时间点
   *
   * @param lastSuccessAt       上次成功拉取的时间，可能为null
   * @param lastErrorAt         上次拉取失败的时间，可能为null
   * @param lastCursor          上次拉取的游标，可能为null
   * @param retryCount          连续拉取失败的次数，必须为非负整数
   * @param leaseUntil          当前租约的截止时间，如果没有被租用则为null
   * @param version             当前版本号，必须为非负整数
   * @param createdAt           创建时间
   * @param updatedAt           最后更新时间
   * @return
   */
  public static SupplierConnection restore(
      Long supplierId,
      String supplierCode,
      ConnectionStatus status,
      int pullIntervalSeconds,
      LocalDateTime nextPullAt,
      LocalDateTime lastSuccessAt,
      LocalDateTime lastErrorAt,
      String lastCursor,
      int retryCount,
      LocalDateTime leaseUntil,
      long version,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    return new SupplierConnection(
        supplierId,
        supplierCode,
        status,
        pullIntervalSeconds,
        nextPullAt,
        lastSuccessAt,
        lastErrorAt,
        lastCursor,
        retryCount,
        leaseUntil,
        version,
        createdAt,
        updatedAt);
  }

  /**
   * 判断在给定时间点是否可以调度该供应商连接。
   *
   * @param now 当前时间，必须不为null
   * @return 是否可以调度
   */
  public boolean isSchedulableAt(LocalDateTime now) {
    Objects.requireNonNull(now, "now must not be null");
    return this.status == ConnectionStatus.ACTIVE
        && !this.nextPullAt.isAfter(now)
        && (this.leaseUntil == null || !this.leaseUntil.isAfter(now));
  }

  /**
   * 尝试获取该供应商连接的租约，成功则更新租约截止时间为leaseUntil。
   *
   * @param leaseUntil 租约截止时间，必须不为null且在未来
   */
  public void acquireLease(LocalDateTime leaseUntil) {
    Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
    if (this.status != ConnectionStatus.ACTIVE) {
      throw new IllegalStateException("Only ACTIVE connections can acquire lease");
    }
    if (this.leaseUntil != null && this.leaseUntil.isAfter(LocalDateTime.now())) {
      throw new IllegalStateException("Connection already has an active lease");
    }
    if (!leaseUntil.isAfter(LocalDateTime.now())) {
      throw new IllegalArgumentException("leaseUntil must be in the future");
    }
    this.leaseUntil = leaseUntil;
    touch();
  }

  /**
   * 释放租约，将leaseUntil设置为null，使该连接可以被其他调度器获取。
   * 只有在当前连接状态为ACTIVE且租约未过期的情况下才允许调用该方法。
   */
  public void releaseLease() {
    this.leaseUntil = null;
    touch();
  }

  public void markPullSucceeded(String nextCursor, LocalDateTime successAt, LocalDateTime nextPullAt) {
    Objects.requireNonNull(successAt, "successAt must not be null");
    Objects.requireNonNull(nextPullAt, "nextPullAt must not be null");
    if (this.status != ConnectionStatus.ACTIVE) {
      throw new IllegalStateException("Only ACTIVE connections can mark pull success");
    }
    if (nextPullAt.isBefore(successAt)) {
      throw new IllegalArgumentException("nextPullAt must not be before successAt");
    }
    this.lastCursor = nextCursor;
    this.lastSuccessAt = successAt;
    this.retryCount = 0;
    this.leaseUntil = null;
    this.nextPullAt = nextPullAt;
    touch();
  }

  public void markPullFailed(LocalDateTime errorAt, LocalDateTime retryAt) {
    Objects.requireNonNull(errorAt, "errorAt must not be null");
    Objects.requireNonNull(retryAt, "retryAt must not be null");
    if (this.status != ConnectionStatus.ACTIVE) {
      throw new IllegalStateException("Only ACTIVE connections can mark pull failure");
    }
    if (retryAt.isBefore(errorAt)) {
      throw new IllegalArgumentException("retryAt must not be before errorAt");
    }
    this.lastErrorAt = errorAt;
    this.retryCount++;
    this.leaseUntil = null;
    this.nextPullAt = retryAt;
    touch();
  }

  public void pause() {
    if (this.status == ConnectionStatus.DISABLED) {
      throw new IllegalStateException("DISABLED connections cannot move back to PAUSED");
    }
    this.status = ConnectionStatus.PAUSED;
    this.leaseUntil = null;
    touch();
  }

  public void activate(LocalDateTime nextPullAt) {
    Objects.requireNonNull(nextPullAt, "nextPullAt must not be null");
    if (this.status == ConnectionStatus.DISABLED) {
      throw new IllegalStateException("DISABLED connections cannot move back to ACTIVE");
    }
    this.status = ConnectionStatus.ACTIVE;
    this.nextPullAt = nextPullAt;
    this.leaseUntil = null;
    touch();
  }

  public void disable() {
    this.status = ConnectionStatus.DISABLED;
    this.leaseUntil = null;
    touch();
  }

  /**
   * 根据给定的基准时间计算下一次允许被调度的时间点。
   *
   * @param baseTime 基准时间，必须不为null
   * @return 下一次允许被调度的时间点，等于基准时间加上拉取间隔秒数
   */
  public LocalDateTime calculateNextPullAtFrom(LocalDateTime baseTime) {
    Objects.requireNonNull(baseTime, "baseTime must not be null");
    return baseTime.plusSeconds(this.pullIntervalSeconds);
  }

  public LocalDateTime calculateRetryAtFrom(LocalDateTime baseTime, int retryDelaySeconds) {
    Objects.requireNonNull(baseTime, "baseTime must not be null");
    if (retryDelaySeconds <= 0) {
      throw new IllegalArgumentException("retryDelaySeconds must be positive");
    }
    return baseTime.plusSeconds(retryDelaySeconds);
  }

  /**
   * 更新版本号和更新时间戳，表示该实体状态发生了变化。
   * 该方法应该在每次修改实体状态后调用，以确保版本号递增和更新时间正确。
   */
  private void touch() {
    this.version++;
    this.updatedAt = LocalDateTime.now();
  }

  private void validateRestoredState() {
    if (this.status != ConnectionStatus.ACTIVE && this.leaseUntil != null) {
      throw new IllegalStateException("Only ACTIVE connections may keep a lease");
    }
  }

  private static String requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  private static Long requirePositive(Long value, String message) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  private static int requirePositive(int value, String message) {
    if (value <= 0) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  public Long getSupplierId() {
    return supplierId;
  }

  public String getSupplierCode() {
    return supplierCode;
  }

  public ConnectionStatus getStatus() {
    return status;
  }

  public int getPullIntervalSeconds() {
    return pullIntervalSeconds;
  }

  public LocalDateTime getNextPullAt() {
    return nextPullAt;
  }

  public LocalDateTime getLastSuccessAt() {
    return lastSuccessAt;
  }

  public LocalDateTime getLastErrorAt() {
    return lastErrorAt;
  }

  public String getLastCursor() {
    return lastCursor;
  }

  public int getRetryCount() {
    return retryCount;
  }

  public LocalDateTime getLeaseUntil() {
    return leaseUntil;
  }

  public long getVersion() {
    return version;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public enum ConnectionStatus {
    ACTIVE,
    PAUSED,
    DISABLED
  }
}
