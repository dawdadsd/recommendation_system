package xiaowu.example.payment.domain.entity;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 支付订单领域对象。
 *
 * <p>
 * 这个类是整个“支付幂等”教学示例的业务核心。
 * 先有它，后面才能安全接入 Kafka、Redisson、数据库、回调通知、主动查询。
 *
 * <p>
 * 请注意：
 * 这个类不是为了演示“怎么把字段塞进一个 Java Bean”，
 * 而是为了把“订单状态只能单向流转”这件事收紧。
 *
 * <p>
 * 在支付系统里，真正防止重复扣款、重复发货、重复加积分的，
 * 不是 Controller，不是 MQ，也不是分布式锁本身，
 * 而是这个订单对象对状态流转的严格约束。
 *
 * <p>
 * 设计目标：
 * 1. 同一个支付订单只能被创建一次。
 * 2. 同一个支付订单只能从 UNPAID -> PAYING -> SUCCESS / CLOSED 单向流转。
 * 3. 已经 SUCCESS 或 CLOSED 的订单，不能再被重复推进。
 * 4. 所有状态变更都必须显式校验，不能偷偷改字段。
 *
 * @author xiaowu
 */
public class PaymentOrder {

  /**
   * 业务订单号。
   *
   * <p>
   * 它是支付幂等的第一关键键。
   * 无论客户端重试多少次，只要还是同一笔支付，就必须携带同一个 orderNo。
   */
  private final String orderNo;

  /**
   * 幂等键。
   *
   * <p>
   * 它通常由客户端在发起支付前向服务端申请，
   * 或者由服务端创建订单时同步生成。
   *
   * <p>
   * orderNo 和 idempotencyKey 可以设计成同一个值，也可以分开。
   * 这里保留两个字段，是为了教学时把“业务订单号”和“防重键”区分清楚。
   */
  private final String idempotencyKey;

  /**
   * 用户 ID。
   */
  private final Long userId;

  /**
   * 商品编码。
   *
   * <p>
   * 为了贴近推荐系统场景，这里你可以理解成：
   * 用户购买“推荐会员包”或“高级推荐分析服务”。
   */
  private final String productCode;

  /**
   * 支付金额，单位：分。
   *
   * <p>
   * 支付系统里通常使用整数分，不建议直接用 double 表示金额。
   */
  private final Long amountFen;

  /**
   * 当前支付状态。
   */
  private PaymentStatus status;

  /**
   * 第三方支付流水号。
   *
   * <p>
   * 只有支付成功后才应该被写入。
   * 它常用于对账、回调幂等、渠道追踪。
   */
  private String channelTradeNo;

  /**
   * 支付中开始时间。
   *
   * <p>
   * 后续做“超时主动查询”“死单补偿关闭”时会用到它。
   */
  private LocalDateTime payingStartedAt;

  /**
   * 支付成功时间。
   */
  private LocalDateTime paidAt;

  /**
   * 关闭时间。
   */
  private LocalDateTime closedAt;

  /**
   * 创建时间。
   */
  private final LocalDateTime createdAt;

  /**
   * 更新时间。
   *
   * <p>
   * 每次状态流转都应该刷新它。
   */
  private LocalDateTime updatedAt;

  private PaymentOrder(
      String orderNo,
      String idempotencyKey,
      Long userId,
      String productCode,
      Long amountFen,
      PaymentStatus status,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    this.orderNo = requireText(orderNo, "orderNo 不能为空");
    this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey 不能为空");
    this.userId = requirePositive(userId, "userId 必须大于 0");
    this.productCode = requireText(productCode, "productCode 不能为空");
    this.amountFen = requirePositive(amountFen, "amountFen 必须大于 0");
    this.status = Objects.requireNonNull(status, "status 不能为空");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
  }

  /**
   * 创建一笔新的待支付订单。
   *
   * <p>
   * 为什么不用 public 构造器直接 new：
   * 因为支付订单的初始状态必须被固定为 UNPAID，
   * 不能允许外部随便 new 一个 SUCCESS 状态出来。
   */
  public static PaymentOrder create(
      String orderNo,
      String idempotencyKey,
      Long userId,
      String productCode,
      Long amountFen) {

    LocalDateTime now = LocalDateTime.now();
    return new PaymentOrder(
        orderNo,
        idempotencyKey,
        userId,
        productCode,
        amountFen,
        PaymentStatus.UNPAID,
        now,
        now);
  }

  /**
   * 从持久化层恢复支付订单。
   *
   * <p>
   * 为什么必须有这个方法，而不是让 Repository 直接调用 create：
   *
   * <p>
   * 因为 create 表达的是“新建订单”；
   * 而仓储层查询数据库时，表达的是“把历史事实恢复回内存对象”。
   * 这是两个完全不同的语义，不能混用。
   *
   * <p>
   * 如果没有 restore，仓储层只能做两种错误事情：
   * 1. 用反射强塞字段，破坏领域封装。
   * 2. 先 create 再补状态，导致 createdAt、updatedAt、payingStartedAt 等信息失真。
   *
   * <p>
   * 所以工业级代码里，聚合根通常都需要一种“受控恢复”入口。
   */
  public static PaymentOrder restore(
      String orderNo,
      String idempotencyKey,
      Long userId,
      String productCode,
      Long amountFen,
      PaymentStatus status,
      String channelTradeNo,
      LocalDateTime payingStartedAt,
      LocalDateTime paidAt,
      LocalDateTime closedAt,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {

    PaymentOrder paymentOrder = new PaymentOrder(
        orderNo,
        idempotencyKey,
        userId,
        productCode,
        amountFen,
        Objects.requireNonNull(status, "status 不能为空"),
        Objects.requireNonNull(createdAt, "createdAt 不能为空"),
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空"));

    paymentOrder.channelTradeNo = channelTradeNo;
    paymentOrder.payingStartedAt = payingStartedAt;
    paymentOrder.paidAt = paidAt;
    paymentOrder.closedAt = closedAt;

    validateRestoredState(paymentOrder);
    return paymentOrder;
  }

  /**
   * 校验“从数据库恢复出来的订单状态”是否自洽。
   *
   * <p>
   * 这一步很关键。
   * 仓储层不是只负责把数据读出来，还要防止脏数据悄悄污染领域模型。
   *
   * <p>
   * 例如：
   * 1. SUCCESS 状态却没有 paidAt
   * 2. SUCCESS 状态却没有 channelTradeNo
   * 3. CLOSED 状态却没有 closedAt
   * 4. PAYING 状态却没有 payingStartedAt
   *
   * <p>
   * 这些都说明库里的数据已经不满足领域约束，
   * 此时应该尽早失败，而不是把错误状态继续往业务流程里传。
   */
  private static void validateRestoredState(PaymentOrder paymentOrder) {
    switch (paymentOrder.status) {
      case UNPAID -> validateUnpaidState(paymentOrder);
      case PAYING -> validatePayingState(paymentOrder);
      case SUCCESS -> validateSuccessState(paymentOrder);
      case CLOSED -> validateClosedState(paymentOrder);
      default -> throw new IllegalStateException("未知支付状态: " + paymentOrder.status);
    }
  }

  /**
   * 校验待支付状态。
   *
   * <p>
   * 待支付说明订单刚创建，还没有真正进入支付提交流程。
   * 因此它不应该携带支付成功、关闭、渠道流水这些终态信息。
   */
  private static void validateUnpaidState(PaymentOrder paymentOrder) {
    if (paymentOrder.channelTradeNo != null) {
      throw new IllegalStateException("UNPAID 状态不应该存在 channelTradeNo");
    }
    if (paymentOrder.paidAt != null) {
      throw new IllegalStateException("UNPAID 状态不应该存在 paidAt");
    }
    if (paymentOrder.closedAt != null) {
      throw new IllegalStateException("UNPAID 状态不应该存在 closedAt");
    }
  }

  /**
   * 校验支付中状态。
   *
   * <p>
   * 支付中意味着请求已经发出，但结果未知。
   * 因此必须有 payingStartedAt，但不能提前有成功或关闭信息。
   */
  private static void validatePayingState(PaymentOrder paymentOrder) {
    if (paymentOrder.payingStartedAt == null) {
      throw new IllegalStateException("PAYING 状态必须存在 payingStartedAt");
    }
    if (paymentOrder.channelTradeNo != null) {
      throw new IllegalStateException("PAYING 状态不应该存在 channelTradeNo");
    }
    if (paymentOrder.paidAt != null) {
      throw new IllegalStateException("PAYING 状态不应该存在 paidAt");
    }
    if (paymentOrder.closedAt != null) {
      throw new IllegalStateException("PAYING 状态不应该存在 closedAt");
    }
  }

  /**
   * 校验支付成功状态。
   *
   * <p>
   * 按照我们定义的状态机，SUCCESS 一定来自 PAYING。
   * 所以成功订单至少要具备：
   * 1. payingStartedAt
   * 2. 渠道流水号
   * 3. paidAt
   *
   * <p>
   * 并且成功订单绝不能再带 closedAt。
   */
  private static void validateSuccessState(PaymentOrder paymentOrder) {
    if (paymentOrder.payingStartedAt == null) {
      throw new IllegalStateException("SUCCESS 状态必须存在 payingStartedAt");
    }
    if (paymentOrder.channelTradeNo == null || paymentOrder.channelTradeNo.isBlank()) {
      throw new IllegalStateException("SUCCESS 状态必须存在 channelTradeNo");
    }
    if (paymentOrder.paidAt == null) {
      throw new IllegalStateException("SUCCESS 状态必须存在 paidAt");
    }
    if (paymentOrder.closedAt != null) {
      throw new IllegalStateException("SUCCESS 状态不应该存在 closedAt");
    }
  }

  /**
   * 校验关闭状态。
   *
   * <p>
   * 关闭既可能来自 UNPAID，也可能来自 PAYING，
   * 所以这里不强制要求 payingStartedAt 一定存在。
   *
   * <p>
   * 但 CLOSED 一定要有 closedAt，
   * 并且不能带支付成功信息。
   */
  private static void validateClosedState(PaymentOrder paymentOrder) {
    if (paymentOrder.closedAt == null) {
      throw new IllegalStateException("CLOSED 状态必须存在 closedAt");
    }
    if (paymentOrder.channelTradeNo != null) {
      throw new IllegalStateException("CLOSED 状态不应该存在 channelTradeNo");
    }
    if (paymentOrder.paidAt != null) {
      throw new IllegalStateException("CLOSED 状态不应该存在 paidAt");
    }
  }

  /**
   * 发起支付，将订单从 UNPAID 推进到 PAYING。
   *
   * <p>
   * 这个方法通常在“创建支付请求”“向第三方发起扣款”之前调用。
   *
   * <p>
   * 为什么必须显式有 PAYING：
   * 因为真实系统里最麻烦的状态不是成功和失败，而是“处理中”。
   * 没有 PAYING，你就没法区分：
   * 1. 还没发起支付
   * 2. 已经发起，但结果未知
   * 3. 可以重试，还是应该等待补偿任务查询
   */
  public void markPaying() {
    assertCurrentStatus(PaymentStatus.UNPAID, "只有待支付订单才能进入支付中");
    this.status = PaymentStatus.PAYING;
    this.payingStartedAt = LocalDateTime.now();
    this.updatedAt = this.payingStartedAt;
  }

  /**
   * 标记支付成功。
   *
   * <p>
   * 这个方法通常由以下入口触发：
   * 1. 支付回调
   * 2. 主动查询渠道订单结果
   * 3. 支付网关同步返回且确认成功
   *
   * <p>
   * 注意这里允许从 PAYING -> SUCCESS，
   * 不允许从 UNPAID 直接跳 SUCCESS。
   * 这样设计是为了让状态机保持严格且易审计。
   */
  public void markSuccess(String channelTradeNo, LocalDateTime paidAt) {
    assertCurrentStatus(PaymentStatus.PAYING, "只有支付中的订单才能标记成功");
    this.channelTradeNo = requireText(channelTradeNo, "channelTradeNo 不能为空");
    this.paidAt = Objects.requireNonNull(paidAt, "paidAt 不能为空");
    this.status = PaymentStatus.SUCCESS;
    this.updatedAt = this.paidAt;
  }

  /**
   * 关闭订单。
   *
   * <p>
   * 典型场景：
   * 1. 用户长时间未支付
   * 2. 主动查询后确认渠道未扣款
   * 3. 风控或业务主动取消
   *
   * <p>
   * 这里只允许 PAYING -> CLOSED 或 UNPAID -> CLOSED。
   * 已经成功的订单绝对不能再关闭。
   */
  public void markClosed() {
    if (this.status != PaymentStatus.UNPAID && this.status != PaymentStatus.PAYING) {
      throw new IllegalStateException("只有待支付或支付中的订单才能关闭，当前状态: " + this.status);
    }
    this.status = PaymentStatus.CLOSED;
    this.closedAt = LocalDateTime.now();
    this.updatedAt = this.closedAt;
  }

  /**
   * 判断这笔订单是否已经进入终态。
   *
   * <p>
   * 终态一旦成立，说明后续所有重复消息、重复回调、重复重试，
   * 都必须被幂等拦截。
   */
  public boolean isTerminal() {
    return this.status == PaymentStatus.SUCCESS || this.status == PaymentStatus.CLOSED;
  }

  /**
   * 判断当前订单是否还允许进入支付流程。
   */
  public boolean canStartPaying() {
    return this.status == PaymentStatus.UNPAID;
  }

  /**
   * 判断当前订单是否允许成功回写。
   */
  public boolean canMarkSuccess() {
    return this.status == PaymentStatus.PAYING;
  }

  /**
   * 判断是否为同一业务请求。
   *
   * <p>
   * 这个方法在服务端处理客户端超时重试时很重要。
   * 如果同一个订单号携带的幂等键都不一致，说明客户端行为已经不安全，
   * 服务端应直接拒绝，而不是“猜测”它是不是同一次支付。
   */
  public boolean matchesIdempotencyKey(String idempotencyKey) {
    return this.idempotencyKey.equals(idempotencyKey);
  }

  private void assertCurrentStatus(PaymentStatus expected, String message) {
    if (this.status != expected) {
      throw new IllegalStateException(message + "，当前状态: " + this.status);
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

  public String getOrderNo() {
    return orderNo;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public Long getUserId() {
    return userId;
  }

  public String getProductCode() {
    return productCode;
  }

  public Long getAmountFen() {
    return amountFen;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public String getChannelTradeNo() {
    return channelTradeNo;
  }

  public LocalDateTime getPayingStartedAt() {
    return payingStartedAt;
  }

  public LocalDateTime getPaidAt() {
    return paidAt;
  }

  public LocalDateTime getClosedAt() {
    return closedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  /**
   * 支付状态枚举。
   *
   * <p>
   * 我把它写成内部枚举，而不是单独拆一个文件，
   * 不是因为拆不开，而是因为当前阶段我们只想把注意力锁定在“订单状态机”本身。
   * 等这个教学链条完整后，如果状态被多处复用，再独立拆出去更合理。
   */
  public enum PaymentStatus {
    /**
     * 待支付：订单已创建，但还没有真正进入支付提交流程。
     */
    UNPAID,

    /**
     * 支付中：请求已经发往渠道，但最终结果还不确定。
     */
    PAYING,

    /**
     * 支付成功：订单已经完成支付，后续只能做幂等返回，不能重复推进。
     */
    SUCCESS,

    /**
     * 已关闭：订单被取消或超时关闭，不允许再继续支付。
     */
    CLOSED
  }
}
