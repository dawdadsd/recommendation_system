package xiaowu.example.payment.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import xiaowu.example.payment.domain.entity.PaymentOrder;
import xiaowu.example.payment.domain.entity.PaymentOrder.PaymentStatus;

/**
 * 支付订单仓储接口。
 *
 * <p>
 * 这个接口不是“为了分层而分层”，而是为了把支付系统真正需要的数据操作能力
 * 明确成契约，避免后面的 Service、Kafka 消费者、回调处理器各自乱写 SQL。
 *
 * <p>
 * 在支付幂等场景里，Repository 至少要解决四类问题：
 *
 * <p>
 * 1. 按业务单号查询：
 * 用于识别“这是不是同一笔支付请求”。
 *
 * <p>
 * 2. 创建订单：
 * 用于第一次落库，建立幂等基石。
 *
 * <p>
 * 3. 按旧状态做条件更新：
 * 用于实现状态机单向流转，防止并发重复推进。
 *
 * <p>
 * 4. 查询超时中的订单：
 * 用于后续补偿任务主动查询支付结果。
 *
 * <p>
 * 注意：
 * 这个接口现在还不关心 Redisson，也不关心 Kafka。
 * 因为它表达的是“数据真相层”的能力，而不是“并发协调层”或“消息接入层”。
 */
public interface PaymentOrderRepository {

  /**
   * 按业务订单号查询订单。
   *
   * <p>
   * 这是幂等判断的第一入口。
   * 当客户端超时重试、消息重复投递、支付回调再次到达时，
   * 业务代码第一步通常都是先查这笔订单是否已经存在。
   *
   * <p>
   * 为什么按 orderNo 查，而不是按数据库自增主键查：
   * 因为支付幂等是围绕“业务唯一单号”建立的，
   * 客户端和外部支付渠道都不会知道你本地数据库的自增 ID。
   */
  Optional<PaymentOrder> findByOrderNo(String orderNo);

  /**
   * 创建新订单。
   *
   * <p>
   * 这是“第一次提交支付请求”的落库动作。
   *
   * <p>
   * 这里故意返回 boolean，而不是直接返回主键，
   * 是为了突出“是否创建成功”这个语义。
   * 在支付幂等里，我们更关心：
   * 1. 这笔订单是否第一次创建成功
   * 2. 是否已经因为重复请求而存在
   *
   * <p>
   * 后续实现时，这里通常会依赖数据库唯一索引保证 orderNo 唯一。
   * 一旦插入冲突，就应该返回 false 或抛出明确异常，而不是偷偷覆盖原订单。
   */
  boolean save(PaymentOrder paymentOrder);

  /**
   * 将订单从 UNPAID 推进到 PAYING。
   *
   * <p>这个方法单独存在，不复用通用的 updateStatus，
   * 因为进入 PAYING 时不仅要改状态，还要记录支付开始时间。
   *
   * <p>如果不把 payingStartedAt 一起落库，后面的超时补偿扫描就没有可靠依据，
   * “支付中多久了”这件事会失真。
   */
  boolean markPaying(
      String orderNo,
      PaymentStatus expectedStatus,
      LocalDateTime payingStartedAt,
      LocalDateTime updatedAt);

  /**
   * 将订单状态从一个旧状态推进到一个新状态。
   *
   * <p>
   * 这是整个支付幂等里最关键的 Repository 方法。
   *
   * <p>
   * 它背后的典型 SQL 语义是：
   *
   * <pre>
   * UPDATE payment_order
   * SET status = ?
   * WHERE order_no = ? AND status = ?
   * </pre>
   *
   * <p>
   * 为什么一定要带 expectedStatus：
   *
   * <p>
   * 因为支付系统不是“最后一次写入生效”就行，
   * 而是必须保证状态只能按照业务允许的方向推进。
   *
   * <p>
   * 如果更新返回 0，通常有两种含义：
   * 1. 订单不存在
   * 2. 订单已经被其他线程推进过，不再处于预期旧状态
   *
   * <p>
   * 这正是我们需要的幂等信号。
   */
  boolean updateStatus(
      String orderNo,
      PaymentStatus expectedStatus,
      PaymentStatus targetStatus,
      LocalDateTime updatedAt);

  /**
   * 将订单从 PAYING 标记为 SUCCESS。
   *
   * <p>
   * 为什么不复用通用 updateStatus：
   *
   * <p>
   * 因为成功回写不仅要改状态，
   * 还要一并写入：
   * 1. 第三方支付流水号
   * 2. 支付成功时间
   * 3. 更新时间
   *
   * <p>
   * 这类更新是支付成功场景的专属能力，单独定义更清晰。
   *
   * <p>
   * 后续实现时，这个方法必须保证只有 PAYING -> SUCCESS 才能成功。
   * 如果它返回 false，说明这笔订单已经被其他线程处理过，
   * 当前线程必须立即停止后续发货、加积分、发通知等动作。
   */
  boolean markSuccess(
      String orderNo,
      PaymentStatus expectedStatus,
      String channelTradeNo,
      LocalDateTime paidAt,
      LocalDateTime updatedAt);

  /**
   * 将订单关闭。
   *
   * <p>
   * 这个方法主要给两类流程使用：
   *
   * <p>
   * 1. 用户长时间未支付，系统主动关闭订单。
   *
   * <p>
   * 2. 补偿任务查询渠道后确认未支付，可以关闭旧单。
   *
   * <p>
   * 这里仍然必须带 expectedStatus，
   * 因为“关闭订单”也必须遵循状态机，而不是随便关。
   *
   * <p>
   * 例如：
   * SUCCESS 状态的订单绝不能被关闭。
   */
  boolean markClosed(
      String orderNo,
      PaymentStatus expectedStatus,
      LocalDateTime closedAt,
      LocalDateTime updatedAt);

  /**
   * 查询那些长时间处于 PAYING 状态的订单。
   *
   * <p>
   * 这个能力是为“主动查询补偿任务”准备的。
   *
   * <p>
   * 典型使用方式：
   * 定时任务扫描所有“支付中且开始时间早于某个阈值”的订单，
   * 再主动调用第三方支付渠道查询真实支付结果。
   *
   * <p>
   * 为什么这个方法现在就要定义出来：
   * 因为它是支付系统闭环的一部分。
   * 如果 Repository 一开始没有这个能力，
   * 后面补偿任务很容易绕过仓储层，直接在业务代码里手写查询逻辑，导致职责混乱。
   */
  java.util.List<PaymentOrder> findPayingOrdersStartedBefore(LocalDateTime deadline, int limit);
}
