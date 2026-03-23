package xiaowu.example.payment.seckill.domain.entity;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 秒杀库存实体
 * 代表某个秒杀活动中某个商品的库存状态
 */
public class SeckillStock {

  private final Long skuId;
  private final Long activityId;
  private final int totalStock;
  private int availableStock;
  private int reservedStock;
  private int soldStock;
  private long version;
  private LocalDateTime updatedAt;

  private SeckillStock(
      Long skuId,
      Long activityId,
      int totalStock,
      int availableStock,
      int reservedStock,
      int soldStock,
      long version,
      LocalDateTime updatedAt) {

    this.skuId = requirePositive(skuId, "skuId must be positive");
    this.activityId = requirePositive(activityId, "activityId must be positive");
    this.totalStock = requireNonNegative(totalStock, "totalStock must not be negative");
    this.availableStock = requireNonNegative(availableStock, "availableStock must not be negative");
    this.reservedStock = requireNonNegative(reservedStock, "reservedStock must not be negative");
    this.soldStock = requireNonNegative(soldStock, "soldStock must not be negative");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    this.version = version;
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    validateInvariant();
  }

  /**
   * 创建秒杀库存实体
   *
   * @param skuId      商品ID
   * @param activityId 秒杀活动ID
   * @param totalStock 总库存
   * @return 秒杀库存实体
   */

  public static SeckillStock create(Long skuId, Long activityId, int totalStock) {
    return new SeckillStock(
        skuId,
        activityId,
        totalStock,
        totalStock,
        0,
        0,
        0,
        LocalDateTime.now());
  }

  /**
   * 从持久化数据恢复秒杀库存实体
   *
   * @param skuId          商品ID
   * @param activityId     秒杀活动ID
   * @param totalStock     总库存
   * @param availableStock 可用库存
   * @param reservedStock  已预订库存
   * @param soldStock      已售出库存
   * @param version        版本号
   * @param updatedAt      更新时间
   * @return 秒杀库存实体
   */
  public static SeckillStock restore(
      Long skuId,
      Long activityId,
      int totalStock,
      int availableStock,
      int reservedStock,
      int soldStock,
      long version,
      LocalDateTime updatedAt) {
    return new SeckillStock(
        skuId,
        activityId,
        totalStock,
        availableStock,
        reservedStock,
        soldStock,
        version,
        updatedAt);
  }

  /**
   * 预订一个库存
   */
  public void reserveOne() {
    if (this.availableStock <= 0) {
      throw new IllegalStateException("No available stock left");
    }
    this.availableStock--;
    this.reservedStock++;
    touch();
  }

  /**
   * 确认已售出一个库存
   */
  public void confirmSold() {
    if (this.reservedStock <= 0) {
      throw new IllegalStateException("No reserved stock to confirm");
    }
    this.reservedStock--;
    this.soldStock++;
    touch();
  }

  /**
   * 释放已预订的库存
   */
  public void releaseReserved() {
    if (this.reservedStock <= 0) {
      throw new IllegalStateException("No reserved stock to release");
    }
    this.reservedStock--;
    this.availableStock++;
    touch();
  }

  /**
   * 判断是否有可用库存
   *
   * @return 是否有可用库存
   */

  public boolean hasAvailableStock() {
    return this.availableStock > 0;
  }

  /**
   * 更新库存状态
   */
  private void touch() {
    validateInvariant();
    this.version++;
    this.updatedAt = LocalDateTime.now();
  }

  /**
   * 验证库存不变量
   */
  private void validateInvariant() {
    if (this.availableStock + this.reservedStock + this.soldStock != this.totalStock) {
      throw new IllegalStateException("Stock invariant violated");
    }
  }

  /**
   * 验证数值参数为正数
   *
   * @param value   待验证的数值参数
   * @param message 错误消息
   * @return 原始数值参数，如果验证通过
   */
  private static Long requirePositive(Long value, String message) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  /**
   * 验证数值参数为非负数
   *
   * @param value   待验证的数值参数
   * @param message 错误消息
   * @return 原始数值参数，如果验证通过
   */
  private static int requireNonNegative(int value, String message) {
    if (value < 0) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  public Long getSkuId() {
    return skuId;
  }

  public Long getActivityId() {
    return activityId;
  }

  public int getTotalStock() {
    return totalStock;
  }

  public int getAvailableStock() {
    return availableStock;
  }

  public int getReservedStock() {
    return reservedStock;
  }

  public int getSoldStock() {
    return soldStock;
  }

  public long getVersion() {
    return version;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
