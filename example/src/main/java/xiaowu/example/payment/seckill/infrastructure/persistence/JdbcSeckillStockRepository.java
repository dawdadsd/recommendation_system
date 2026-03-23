package xiaowu.example.payment.seckill.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import xiaowu.example.payment.seckill.domain.entity.SeckillStock;
import xiaowu.example.payment.seckill.domain.repository.SeckillStockRepository;

/**
 * 基于 JDBC 的秒杀库存仓储实现。
 *
 * <p>
 * 秒杀库存最关键的不是“查库存”，而是三种原子推进：
 *
 * <p>
 * 1. 可售 -> 冻结
 * <p>
 * 2. 冻结 -> 已售
 * <p>
 * 3. 冻结 -> 回补可售
 *
 * <p>
 * 这里统一用 version 做乐观并发控制，避免多个线程同时把同一份库存推进两次。
 */
@Repository
@RequiredArgsConstructor
public class JdbcSeckillStockRepository implements SeckillStockRepository {

  private static final String FIND_BY_SKU_ID_SQL = """
      SELECT
          sku_id,
          activity_id,
          total_stock,
          available_stock,
          reserved_stock,
          sold_stock,
          version,
          updated_at
      FROM seckill_stock
      WHERE sku_id = ?
      """;

  private static final String RESERVE_STOCK_SQL = """
      UPDATE seckill_stock
      SET
          available_stock = available_stock - 1,
          reserved_stock = reserved_stock + 1,
          version = version + 1,
          updated_at = ?
      WHERE sku_id = ?
        AND available_stock > 0
        AND version = ?
      """;

  private static final String CONFIRM_SOLD_SQL = """
      UPDATE seckill_stock
      SET
          reserved_stock = reserved_stock - 1,
          sold_stock = sold_stock + 1,
          version = version + 1,
          updated_at = ?
      WHERE sku_id = ?
        AND reserved_stock > 0
        AND version = ?
      """;

  private static final String RELEASE_RESERVED_STOCK_SQL = """
      UPDATE seckill_stock
      SET
          reserved_stock = reserved_stock - 1,
          available_stock = available_stock + 1,
          version = version + 1,
          updated_at = ?
      WHERE sku_id = ?
        AND reserved_stock > 0
        AND version = ?
      """;

  private final JdbcTemplate jdbcTemplate;

  private final RowMapper<SeckillStock> stockRowMapper = new SeckillStockRowMapper();

  @Override
  public Optional<SeckillStock> findBySkuId(Long skuId) {
    List<SeckillStock> results = jdbcTemplate.query(
        FIND_BY_SKU_ID_SQL,
        stockRowMapper,
        skuId);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  @Override
  public boolean reserveStock(Long skuId, long expectedVersion, LocalDateTime updatedAt) {
    int affectedRows = jdbcTemplate.update(
        RESERVE_STOCK_SQL,
        toTimestamp(updatedAt),
        skuId,
        expectedVersion);
    return affectedRows > 0;
  }

  @Override
  public boolean confirmSold(Long skuId, long expectedVersion, LocalDateTime updatedAt) {
    int affectedRows = jdbcTemplate.update(
        CONFIRM_SOLD_SQL,
        toTimestamp(updatedAt),
        skuId,
        expectedVersion);
    return affectedRows > 0;
  }

  @Override
  public boolean releaseReservedStock(Long skuId, long expectedVersion, LocalDateTime updatedAt) {
    int affectedRows = jdbcTemplate.update(
        RELEASE_RESERVED_STOCK_SQL,
        toTimestamp(updatedAt),
        skuId,
        expectedVersion);
    return affectedRows > 0;
  }

  private static Timestamp toTimestamp(LocalDateTime value) {
    return value == null ? null : Timestamp.valueOf(value);
  }

  private static final class SeckillStockRowMapper implements RowMapper<SeckillStock> {

    @Override
    public SeckillStock mapRow(ResultSet rs, int rowNum) throws SQLException {
      return SeckillStock.restore(
          rs.getLong("sku_id"),
          rs.getLong("activity_id"),
          rs.getInt("total_stock"),
          rs.getInt("available_stock"),
          rs.getInt("reserved_stock"),
          rs.getInt("sold_stock"),
          rs.getLong("version"),
          toLocalDateTime(rs.getTimestamp("updated_at")));
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
      return value == null ? null : value.toLocalDateTime();
    }
  }
}
