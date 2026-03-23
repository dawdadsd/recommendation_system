package xiaowu.example.payment.seckill.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import xiaowu.example.payment.seckill.domain.entity.SeckillReservation;
import xiaowu.example.payment.seckill.domain.entity.SeckillReservation.ReservationStatus;
import xiaowu.example.payment.seckill.domain.repository.SeckillReservationRepository;

/**
 * 基于 JDBC 的秒杀资格仓储实现。
 *
 * <p>
 * 这里的重点不是简单 CRUD，而是把资格状态机真正落到数据库条件更新里。
 * 只要 SQL 里带上 expectedStatus，就能防止重复消息或并发线程把资格推进两次。
 */
@Repository
@RequiredArgsConstructor
public class JdbcSeckillReservationRepository implements SeckillReservationRepository {

  private static final String FIND_BY_RESERVATION_ID_SQL = """
      SELECT
          reservation_id,
          activity_id,
          sku_id,
          user_id,
          reservation_token,
          status,
          payment_order_no,
          expire_at,
          released_at,
          created_at,
          updated_at
      FROM seckill_reservation
      WHERE reservation_id = ?
      """;

  private static final String FIND_BY_USER_SQL = """
      SELECT
          reservation_id,
          activity_id,
          sku_id,
          user_id,
          reservation_token,
          status,
          payment_order_no,
          expire_at,
          released_at,
          created_at,
          updated_at
      FROM seckill_reservation
      WHERE activity_id = ?
        AND sku_id = ?
        AND user_id = ?
      """;

  private static final String INSERT_SQL = """
      INSERT INTO seckill_reservation (
          reservation_id,
          activity_id,
          sku_id,
          user_id,
          reservation_token,
          status,
          payment_order_no,
          expire_at,
          released_at,
          created_at,
          updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String BIND_PAYMENT_ORDER_SQL = """
      UPDATE seckill_reservation
      SET
          status = ?,
          payment_order_no = ?,
          updated_at = ?
      WHERE reservation_id = ?
        AND status = ?
      """;

  private static final String UPDATE_STATUS_SQL = """
      UPDATE seckill_reservation
      SET
          status = ?,
          updated_at = ?
      WHERE reservation_id = ?
        AND status = ?
      """;

  private static final String MARK_RELEASED_SQL = """
      UPDATE seckill_reservation
      SET
          status = ?,
          released_at = ?,
          updated_at = ?
      WHERE reservation_id = ?
        AND status = ?
      """;

  private final JdbcTemplate jdbcTemplate;

  private final RowMapper<SeckillReservation> reservationRowMapper = new SeckillReservationRowMapper();

  @Override
  public Optional<SeckillReservation> findByReservationId(String reservationId) {
    List<SeckillReservation> results = jdbcTemplate.query(
        FIND_BY_RESERVATION_ID_SQL,
        reservationRowMapper,
        reservationId);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  @Override
  public Optional<SeckillReservation> findByUser(Long activityId, Long skuId, Long userId) {
    List<SeckillReservation> results = jdbcTemplate.query(
        FIND_BY_USER_SQL,
        reservationRowMapper,
        activityId,
        skuId,
        userId);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  @Override
  public boolean saveIfAbsent(SeckillReservation reservation) {
    try {
      int affectedRows = jdbcTemplate.update(
          INSERT_SQL,
          reservation.getReservationId(),
          reservation.getActivityId(),
          reservation.getSkuId(),
          reservation.getUserId(),
          reservation.getReservationToken(),
          reservation.getStatus().name(),
          reservation.getPaymentOrderNo(),
          toTimestamp(reservation.getExpireAt()),
          toTimestamp(reservation.getReleasedAt()),
          toTimestamp(reservation.getCreatedAt()),
          toTimestamp(reservation.getUpdatedAt()));
      return affectedRows > 0;
    } catch (DuplicateKeyException ex) {
      // reservationId 或用户唯一索引冲突，都说明这次不是首个成功请求。
      return false;
    }
  }

  @Override
  public boolean bindPaymentOrder(
      String reservationId,
      ReservationStatus expectedStatus,
      String paymentOrderNo,
      LocalDateTime updatedAt) {

    int affectedRows = jdbcTemplate.update(
        BIND_PAYMENT_ORDER_SQL,
        ReservationStatus.ORDER_CREATED.name(),
        paymentOrderNo,
        toTimestamp(updatedAt),
        reservationId,
        expectedStatus.name());
    return affectedRows > 0;
  }

  @Override
  public boolean updateStatus(
      String reservationId,
      ReservationStatus expectedStatus,
      ReservationStatus targetStatus,
      LocalDateTime updatedAt) {

    int affectedRows = jdbcTemplate.update(
        UPDATE_STATUS_SQL,
        targetStatus.name(),
        toTimestamp(updatedAt),
        reservationId,
        expectedStatus.name());
    return affectedRows > 0;
  }

  @Override
  public boolean markReleased(
      String reservationId,
      ReservationStatus expectedStatus,
      LocalDateTime releasedAt,
      LocalDateTime updatedAt) {

    int affectedRows = jdbcTemplate.update(
        MARK_RELEASED_SQL,
        ReservationStatus.RELEASED.name(),
        toTimestamp(releasedAt),
        toTimestamp(updatedAt),
        reservationId,
        expectedStatus.name());
    return affectedRows > 0;
  }

  private static Timestamp toTimestamp(LocalDateTime value) {
    return value == null ? null : Timestamp.valueOf(value);
  }

  /**
   * 行映射时仍然调用领域对象的 restore，避免仓储层绕过领域约束直接拼对象。
   */
  private static final class SeckillReservationRowMapper implements RowMapper<SeckillReservation> {

    @Override
    public SeckillReservation mapRow(ResultSet rs, int rowNum) throws SQLException {
      return SeckillReservation.restore(
          rs.getString("reservation_id"),
          rs.getLong("activity_id"),
          rs.getLong("sku_id"),
          rs.getLong("user_id"),
          rs.getString("reservation_token"),
          ReservationStatus.valueOf(rs.getString("status")),
          rs.getString("payment_order_no"),
          toLocalDateTime(rs.getTimestamp("expire_at")),
          toLocalDateTime(rs.getTimestamp("released_at")),
          toLocalDateTime(rs.getTimestamp("created_at")),
          toLocalDateTime(rs.getTimestamp("updated_at")));
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
      return value == null ? null : value.toLocalDateTime();
    }
  }
}
