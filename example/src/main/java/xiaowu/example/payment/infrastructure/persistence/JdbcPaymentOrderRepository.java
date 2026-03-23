package xiaowu.example.payment.infrastructure.persistence;

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
import xiaowu.example.payment.domain.entity.PaymentOrder;
import xiaowu.example.payment.domain.entity.PaymentOrder.PaymentStatus;
import xiaowu.example.payment.domain.repository.PaymentOrderRepository;

/**
 * 基于 JDBC 的支付订单仓储实现。
 *
 * <p>
 * 这是支付幂等链路里非常关键的一层。
 * 前面的领域对象负责“状态机是否合法”，
 * 这一层负责把“状态机护栏”真正落实到数据库更新条件里。
 *
 * <p>
 * 你要重点看三类 SQL：
 *
 * <p>
 * 1. 查询类 SQL
 * 用于识别订单是否存在、订单当前处于什么状态。
 *
 * <p>
 * 2. 插入类 SQL
 * 用于建立幂等基石，也就是“同一个 orderNo 只能创建一次”。
 *
 * <p>
 * 3. 条件更新类 SQL
 * 用于实现乐观幂等控制。
 * 真正的核心不是 update，而是 update 后面的旧状态条件。
 *
 * <p>
 * 注意：
 * 这里即使未来加了 Redisson，这些 SQL 也不能删。
 * 因为锁只能降低并发竞争，数据库条件更新才是最终事实护栏。
 */
@Repository
@RequiredArgsConstructor
public class JdbcPaymentOrderRepository implements PaymentOrderRepository {

    /**
     * 按业务订单号查询订单。
     */
    private static final String FIND_BY_ORDER_NO_SQL = """
            SELECT
                order_no,
                idempotency_key,
                user_id,
                product_code,
                amount_fen,
                status,
                channel_trade_no,
                paying_started_at,
                paid_at,
                closed_at,
                created_at,
                updated_at
            FROM payment_order
            WHERE order_no = ?
            """;

    /**
     * 新建订单。
     *
     * <p>
     * 这里假设 payment_order 表上已经有 order_no 的唯一索引。
     * 这样才能真正把“同一订单只能创建一次”收紧到数据库层。
     */
    private static final String INSERT_SQL = """
            INSERT INTO payment_order (
                order_no,
                idempotency_key,
                user_id,
                product_code,
                amount_fen,
                status,
                channel_trade_no,
                paying_started_at,
                paid_at,
                closed_at,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * 通用状态推进 SQL。
     *
     * <p>
     * 这是最经典的乐观幂等写法：
     * 只有数据库当前状态等于 expectedStatus，才允许推进到 targetStatus。
     *
     * <p>
     * 一旦返回更新行数为 0，就说明：
     * 1. 订单不存在
     * 2. 订单已经被其他线程处理过
     *
     * <p>
     * 这两种情况在支付链路里都意味着“当前线程不能继续推进后续副作用”。
     */
    private static final String UPDATE_STATUS_SQL = """
            UPDATE payment_order
            SET status = ?, updated_at = ?
            WHERE order_no = ? AND status = ?
            """;

    /**
     * 进入支付中状态的专用更新 SQL。
     *
     * <p>
     * 这里必须把 paying_started_at 一起写入，
     * 否则后面“支付中超时补偿扫描”没有可靠的时间基准。
     */
    private static final String MARK_PAYING_SQL = """
            UPDATE payment_order
            SET
                status = ?,
                paying_started_at = ?,
                updated_at = ?
            WHERE order_no = ? AND status = ?
            """;

    /**
     * 支付成功专用更新 SQL。
     *
     * <p>
     * 成功不是单纯改一个状态，
     * 还必须把渠道流水号、支付成功时间一起落库。
     *
     * <p>
     * 为什么也要带旧状态条件：
     * 因为 SUCCESS 只能从 PAYING 来，
     * 绝不能允许 CLOSED 或 SUCCESS 再被重复写成功。
     */
    private static final String MARK_SUCCESS_SQL = """
            UPDATE payment_order
            SET
                status = ?,
                channel_trade_no = ?,
                paid_at = ?,
                updated_at = ?
            WHERE order_no = ? AND status = ?
            """;

    /**
     * 关闭订单专用更新 SQL。
     *
     * <p>
     * 关闭订单时也必须带旧状态条件，
     * 防止已经成功的订单被错误关闭。
     */
    private static final String MARK_CLOSED_SQL = """
            UPDATE payment_order
            SET
                status = ?,
                closed_at = ?,
                updated_at = ?
            WHERE order_no = ? AND status = ?
            """;

    /**
     * 查询超时支付中的订单。
     *
     * <p>
     * 这是后面做“主动查询补偿任务”的基础能力。
     */
    private static final String FIND_PAYING_ORDERS_STARTED_BEFORE_SQL = """
            SELECT
                order_no,
                idempotency_key,
                user_id,
                product_code,
                amount_fen,
                status,
                channel_trade_no,
                paying_started_at,
                paid_at,
                closed_at,
                created_at,
                updated_at
            FROM payment_order
            WHERE status = ?
              AND paying_started_at IS NOT NULL
              AND paying_started_at < ?
            ORDER BY paying_started_at ASC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<PaymentOrder> paymentOrderRowMapper = new PaymentOrderRowMapper();

    @Override
    public Optional<PaymentOrder> findByOrderNo(String orderNo) {
        List<PaymentOrder> results = jdbcTemplate.query(
                FIND_BY_ORDER_NO_SQL,
                paymentOrderRowMapper,
                orderNo);

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public boolean save(PaymentOrder paymentOrder) {
        int affectedRows = jdbcTemplate.update(
                INSERT_SQL,
                paymentOrder.getOrderNo(),
                paymentOrder.getIdempotencyKey(),
                paymentOrder.getUserId(),
                paymentOrder.getProductCode(),
                paymentOrder.getAmountFen(),
                paymentOrder.getStatus().name(),
                paymentOrder.getChannelTradeNo(),
                toTimestamp(paymentOrder.getPayingStartedAt()),
                toTimestamp(paymentOrder.getPaidAt()),
                toTimestamp(paymentOrder.getClosedAt()),
                toTimestamp(paymentOrder.getCreatedAt()),
                toTimestamp(paymentOrder.getUpdatedAt()));

        return affectedRows > 0;
    }

    @Override
    public boolean updateStatus(
            String orderNo,
            PaymentStatus expectedStatus,
            PaymentStatus targetStatus,
            LocalDateTime updatedAt) {

        int affectedRows = jdbcTemplate.update(
                UPDATE_STATUS_SQL,
                targetStatus.name(),
                toTimestamp(updatedAt),
                orderNo,
                expectedStatus.name());

        return affectedRows > 0;
    }

    @Override
    public boolean markPaying(
            String orderNo,
            PaymentStatus expectedStatus,
            LocalDateTime payingStartedAt,
            LocalDateTime updatedAt) {

        int affectedRows = jdbcTemplate.update(
                MARK_PAYING_SQL,
                PaymentStatus.PAYING.name(),
                toTimestamp(payingStartedAt),
                toTimestamp(updatedAt),
                orderNo,
                expectedStatus.name());

        return affectedRows > 0;
    }

    @Override
    public boolean markSuccess(
            String orderNo,
            PaymentStatus expectedStatus,
            String channelTradeNo,
            LocalDateTime paidAt,
            LocalDateTime updatedAt) {

        int affectedRows = jdbcTemplate.update(
                MARK_SUCCESS_SQL,
                PaymentStatus.SUCCESS.name(),
                channelTradeNo,
                toTimestamp(paidAt),
                toTimestamp(updatedAt),
                orderNo,
                expectedStatus.name());

        return affectedRows > 0;
    }

    @Override
    public boolean markClosed(
            String orderNo,
            PaymentStatus expectedStatus,
            LocalDateTime closedAt,
            LocalDateTime updatedAt) {

        int affectedRows = jdbcTemplate.update(
                MARK_CLOSED_SQL,
                PaymentStatus.CLOSED.name(),
                toTimestamp(closedAt),
                toTimestamp(updatedAt),
                orderNo,
                expectedStatus.name());

        return affectedRows > 0;
    }

    @Override
    public List<PaymentOrder> findPayingOrdersStartedBefore(LocalDateTime deadline, int limit) {
        return jdbcTemplate.query(
                FIND_PAYING_ORDERS_STARTED_BEFORE_SQL,
                paymentOrderRowMapper,
                PaymentStatus.PAYING.name(),
                toTimestamp(deadline),
                limit);
    }

    /**
     * 时间转换工具。
     *
     * <p>
     * JDBC Template 最稳妥的做法之一就是在仓储层显式处理时间类型，
     * 避免让业务层关心数据库时间映射细节。
     */
    private static Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    /**
     * 支付订单行映射器。
     *
     * <p>
     * 为什么我把它写成当前类的内部类，而不是立刻拆单独文件：
     * 因为现在它只服务当前仓储实现，职责非常聚焦。
     *
     * <p>
     * 什么时候再拆出去：
     * 当多个 JDBC 仓储都要复用它，或者映射逻辑明显变复杂时。
     */
    private static final class PaymentOrderRowMapper implements RowMapper<PaymentOrder> {

        @Override
        public PaymentOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
            return PaymentOrder.restore(
                    rs.getString("order_no"),
                    rs.getString("idempotency_key"),
                    rs.getLong("user_id"),
                    rs.getString("product_code"),
                    rs.getLong("amount_fen"),
                    PaymentStatus.valueOf(rs.getString("status")),
                    rs.getString("channel_trade_no"),
                    toLocalDateTime(rs.getTimestamp("paying_started_at")),
                    toLocalDateTime(rs.getTimestamp("paid_at")),
                    toLocalDateTime(rs.getTimestamp("closed_at")),
                    toLocalDateTime(rs.getTimestamp("created_at")),
                    toLocalDateTime(rs.getTimestamp("updated_at")));
        }

        private static LocalDateTime toLocalDateTime(Timestamp value) {
            return value == null ? null : value.toLocalDateTime();
        }
    }
}
