package xiaowu.example.payment.application.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import xiaowu.example.payment.domain.entity.PaymentOrder;
import xiaowu.example.payment.domain.entity.PaymentOrder.PaymentStatus;
import xiaowu.example.payment.domain.repository.PaymentOrderRepository;

/**
 * 支付应用服务。
 *
 * <p>
 * 这一层负责“编排业务流程”，而不是承载底层技术细节。
 * 你可以把它理解为：
 *
 * <p>
 * 1. 它知道支付请求应该先查什么、再判什么、最后写什么。
 *
 * <p>
 * 2. 它不直接写 SQL，那是 Repository 的职责。
 *
 * <p>
 * 3. 它也不直接承担分布式锁、Kafka 消费这些入口细节，
 * 那些后面会挂到这一层前面。
 *
 * <p>
 * 当前这一版先把最关键的幂等主流程收紧：
 *
 * <p>
 * - 创建支付单
 * - 发起支付
 * - 支付成功回写
 * - 关闭订单
 *
 * <p>
 * 注意：
 * 这里还没有接 Redisson 和 Kafka，但已经把“相同订单号如何幂等处理”定清楚了。
 * 后面无论是 HTTP 重试、MQ 重复消息还是支付回调重放，最终都要落到这里。
 */
@Service
@RequiredArgsConstructor
public class PaymentApplicationService {

    private final PaymentOrderRepository paymentOrderRepository;

    /**
     * 创建支付订单。
     *
     * <p>
     * 幂等策略：
     *
     * <p>
     * 1. 如果订单不存在，就创建新订单。
     *
     * <p>
     * 2. 如果订单已存在，但幂等键不同，直接拒绝。
     * 这代表客户端已经不再明确自己是否在重试同一笔请求。
     *
     * <p>
     * 3. 如果订单已存在且幂等键相同，直接返回已有订单。
     * 这就是“把重试权利交给网络，把去重责任留给服务端”的第一步。
     */
    @Transactional
    public CreatePaymentResult createOrder(CreatePaymentCommand command) {
        PaymentOrder existingOrder = paymentOrderRepository.findByOrderNo(command.orderNo()).orElse(null);
        if (existingOrder != null) {
            if (!existingOrder.matchesIdempotencyKey(command.idempotencyKey())) {
                throw new IllegalStateException("相同订单号对应了不同幂等键，拒绝处理不安全重试");
            }
            return new CreatePaymentResult(existingOrder, false, "订单已存在，返回历史订单");
        }

        PaymentOrder newOrder = PaymentOrder.create(
                command.orderNo(),
                command.idempotencyKey(),
                command.userId(),
                command.productCode(),
                command.amountFen());

        boolean saved = paymentOrderRepository.save(newOrder);
        if (!saved) {
            throw new IllegalStateException("支付订单创建失败，可能存在唯一键冲突或数据库异常");
        }

        return new CreatePaymentResult(newOrder, true, "订单创建成功");
    }

    /**
     * 发起支付。
     *
     * <p>
     * 幂等策略：
     *
     * <p>
     * 1. 只允许 UNPAID -> PAYING。
     *
     * <p>
     * 2. 如果已经是 PAYING，说明上一次请求已经发出但客户端可能超时了，
     * 当前直接返回“支付中”即可，而不是重复发起新支付。
     *
     * <p>
     * 3. 如果已经 SUCCESS，直接返回成功视图。
     *
     * <p>
     * 4. 如果已经 CLOSED，拒绝再次发起支付。
     */
    @Transactional
    public PaymentProcessResult startPayment(StartPaymentCommand command) {
        PaymentOrder order = getRequiredOrder(command.orderNo());
        assertIdempotencyKey(order, command.idempotencyKey());

        if (order.getStatus() == PaymentStatus.PAYING) {
            return new PaymentProcessResult(order, false, "订单已处于支付中，请等待最终结果");
        }
        if (order.getStatus() == PaymentStatus.SUCCESS) {
            return new PaymentProcessResult(order, false, "订单已支付成功，直接返回成功结果");
        }
        if (order.getStatus() == PaymentStatus.CLOSED) {
            throw new IllegalStateException("订单已关闭，不允许再次发起支付");
        }

        order.markPaying();
        boolean updated = paymentOrderRepository.markPaying(
                order.getOrderNo(),
                PaymentStatus.UNPAID,
                order.getPayingStartedAt(),
                order.getUpdatedAt());

        if (!updated) {
            throw new IllegalStateException("订单进入支付中失败，可能已被其他线程推进");
        }

        return new PaymentProcessResult(order, true, "订单已推进到支付中");
    }

    /**
     * 支付成功回写。
     *
     * <p>
     * 这个方法未来会被三类入口复用：
     *
     * <p>
     * 1. 支付渠道异步回调
     * 2. 主动查询补偿任务
     * 3. 少数同步确认成功场景
     *
     * <p>
     * 幂等策略：
     *
     * <p>
     * - 如果已经 SUCCESS，直接视为幂等成功。
     * - 如果不是 PAYING，拒绝非法流转。
     * - 真正落库时仍然要求 WHERE status = PAYING，防止并发重复推进。
     */
    @Transactional
    public PaymentProcessResult markSuccess(MarkPaymentSuccessCommand command) {
        PaymentOrder order = getRequiredOrder(command.orderNo());

        if (order.getStatus() == PaymentStatus.SUCCESS) {
            return new PaymentProcessResult(order, false, "订单已是成功状态，本次按幂等成功处理");
        }
        if (!order.canMarkSuccess()) {
            throw new IllegalStateException("只有支付中的订单才能回写成功，当前状态: " + order.getStatus());
        }

        LocalDateTime paidAt = command.paidAt() == null ? LocalDateTime.now() : command.paidAt();
        order.markSuccess(command.channelTradeNo(), paidAt);

        boolean updated = paymentOrderRepository.markSuccess(
                order.getOrderNo(),
                PaymentStatus.PAYING,
                order.getChannelTradeNo(),
                order.getPaidAt(),
                order.getUpdatedAt());

        if (!updated) {
            throw new IllegalStateException("订单回写支付成功失败，可能已被其他线程处理");
        }

        return new PaymentProcessResult(order, true, "订单已回写为支付成功");
    }

    /**
     * 关闭订单。
     *
     * <p>
     * 这个方法既可以处理“超时未支付关闭”，
     * 也可以处理“主动查询渠道后确认未支付”的关闭场景。
     *
     * <p>
     * 幂等策略：
     *
     * <p>
     * - 如果已经 CLOSED，直接按幂等成功返回。
     * - 如果已经 SUCCESS，拒绝关闭。
     * - 只允许 UNPAID 或 PAYING 进入 CLOSED。
     */
    @Transactional
    public PaymentProcessResult closeOrder(ClosePaymentCommand command) {
        PaymentOrder order = getRequiredOrder(command.orderNo());

        if (order.getStatus() == PaymentStatus.CLOSED) {
            return new PaymentProcessResult(order, false, "订单已关闭，本次按幂等成功处理");
        }
        if (order.getStatus() == PaymentStatus.SUCCESS) {
            throw new IllegalStateException("支付成功订单不能被关闭");
        }

        PaymentStatus expectedStatus = order.getStatus();
        order.markClosed();

        boolean updated = paymentOrderRepository.markClosed(
                order.getOrderNo(),
                expectedStatus,
                order.getClosedAt(),
                order.getUpdatedAt());

        if (!updated) {
            throw new IllegalStateException("订单关闭失败，可能已被其他线程推进");
        }

        return new PaymentProcessResult(order, true, "订单已关闭");
    }

    /**
     * 查询订单当前状态。
     *
     * <p>
     * 这个方法后面很适合给前端“支付中轮询”接口复用。
     */
    @Transactional(readOnly = true)
    public PaymentOrder getOrder(String orderNo) {
        return getRequiredOrder(orderNo);
    }

    private PaymentOrder getRequiredOrder(String orderNo) {
        return paymentOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalStateException("支付订单不存在: " + orderNo));
    }

    private void assertIdempotencyKey(PaymentOrder order, String idempotencyKey) {
        if (!order.matchesIdempotencyKey(idempotencyKey)) {
            throw new IllegalStateException("幂等键不匹配，拒绝处理可疑重试请求");
        }
    }

    /**
     * 创建支付单命令。
     */
    public record CreatePaymentCommand(
            String orderNo,
            String idempotencyKey,
            Long userId,
            String productCode,
            Long amountFen) {
    }

    /**
     * 发起支付命令。
     */
    public record StartPaymentCommand(
            String orderNo,
            String idempotencyKey) {
    }

    /**
     * 回写支付成功命令。
     */
    public record MarkPaymentSuccessCommand(
            String orderNo,
            String channelTradeNo,
            LocalDateTime paidAt) {
    }

    /**
     * 关闭订单命令。
     */
    public record ClosePaymentCommand(String orderNo) {
    }

    /**
     * 创建支付单结果。
     */
    public record CreatePaymentResult(
            PaymentOrder order,
            boolean created,
            String message) {
    }

    /**
     * 通用流程处理结果。
     */
    public record PaymentProcessResult(
            PaymentOrder order,
            boolean stateChanged,
            String message) {
    }
}
