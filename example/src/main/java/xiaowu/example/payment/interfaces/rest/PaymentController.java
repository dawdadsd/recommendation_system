package xiaowu.example.payment.interfaces.rest;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import xiaowu.example.payment.application.service.PaymentApplicationService;
import xiaowu.example.payment.application.service.PaymentApplicationService.ClosePaymentCommand;
import xiaowu.example.payment.application.service.PaymentApplicationService.CreatePaymentCommand;
import xiaowu.example.payment.application.service.PaymentApplicationService.CreatePaymentResult;
import xiaowu.example.payment.application.service.PaymentApplicationService.MarkPaymentSuccessCommand;
import xiaowu.example.payment.application.service.PaymentApplicationService.PaymentProcessResult;
import xiaowu.example.payment.application.service.PaymentApplicationService.StartPaymentCommand;
import xiaowu.example.payment.domain.entity.PaymentOrder;

/**
 * 支付教学控制器。
 *
 * <p>
 * 这个控制器不是为了模拟完整支付网关，而是为了把当前教学链路暴露成可测试接口。
 * 你启动 ExampleApplication 后，可以直接用这些接口验证：
 *
 * <p>
 * 1. 相同订单号 + 相同幂等键重复创建时，不会生成两笔订单。
 *
 * <p>
 * 2. 订单只能从 UNPAID 进入 PAYING。
 *
 * <p>
 * 3. 只有 PAYING 才能被回写为 SUCCESS。
 *
 * <p>
 * 4. SUCCESS / CLOSED 这类终态订单不会被重复推进。
 *
 * <p>
 * 同时，配合 schema.sql 和 data.sql，
 * 你还可以直接测试“已有支付中订单”“已有成功订单”“已关闭订单”的幂等表现。
 *
 * @author xiaowu
 */
@RestController
@RequestMapping("/api/examples/payments")
@RequiredArgsConstructor
public class PaymentController {

        private final PaymentApplicationService paymentApplicationService;

        /**
         * 创建支付订单。
         *
         * <p>
         * 测试建议：
         * 先用同一组 orderNo + idempotencyKey 连续调用两次，
         * 观察第二次是否返回同一笔历史订单。
         */
        @PostMapping("/orders")
        public CreatePaymentResult createOrder(@RequestBody CreateOrderRequest request) {
                return paymentApplicationService.createOrder(new CreatePaymentCommand(
                                request.orderNo(),
                                request.idempotencyKey(),
                                request.userId(),
                                request.productCode(),
                                request.amountFen()));
        }

        /**
         * 发起支付，将订单推进到 PAYING。
         */
        @PostMapping("/orders/{orderNo}/start")
        public PaymentProcessResult startPayment(
                        @PathVariable String orderNo,
                        @RequestBody StartPaymentRequest request) {
                return paymentApplicationService.startPayment(new StartPaymentCommand(
                                orderNo,
                                request.idempotencyKey()));
        }

        /**
         * 模拟支付渠道回调成功。
         *
         * <p>
         * 你可以把它理解成“微信/支付宝异步通知到达后的业务入口”。
         */
        @PostMapping("/orders/{orderNo}/success")
        public PaymentProcessResult markSuccess(
                        @PathVariable String orderNo,
                        @RequestBody MarkSuccessRequest request) {
                return paymentApplicationService.markSuccess(new MarkPaymentSuccessCommand(
                                orderNo,
                                request.channelTradeNo(),
                                request.paidAt()));
        }

        /**
         * 关闭订单。
         */
        @PostMapping("/orders/{orderNo}/close")
        public PaymentProcessResult closeOrder(@PathVariable String orderNo) {
                return paymentApplicationService.closeOrder(new ClosePaymentCommand(orderNo));
        }

        /**
         * 查询订单当前状态。
         *
         * <p>
         * 这个接口很适合模拟前端“支付中轮询”。
         */
        @GetMapping("/orders/{orderNo}")
        public PaymentOrder getOrder(@PathVariable String orderNo) {
                return paymentApplicationService.getOrder(orderNo);
        }

        /**
         * 返回一组可以直接复制的测试样例。
         *
         * <p>
         * 这个接口的价值是降低你第一次手工测试的心智成本。
         * 启动服务后先看它，再按里面的值去调创建/发起/成功回写接口即可。
         */
        @GetMapping("/demo")
        public Map<String, Object> demo() {
                return Map.of(
                                "seedUsers", new long[] { 1001L, 1002L, 1003L },
                                "seedProducts", new String[] { "VIP_MONTH", "VIP_YEAR", "REPORT_PACK" },
                                "seedOrders",
                                new String[] { "PAY_DEMO_SUCCESS_001", "PAY_DEMO_PAYING_001", "PAY_DEMO_CLOSED_001" },
                                "createOrderExample", Map.of(
                                                "orderNo", "PAY_NEW_20260320_001",
                                                "idempotencyKey", "IDEMP_NEW_20260320_001",
                                                "userId", 1001L,
                                                "productCode", "VIP_MONTH",
                                                "amountFen", 9900L),
                                "startPaymentExample", Map.of(
                                                "requestPath",
                                                "/api/examples/payments/orders/PAY_NEW_20260320_001/start",
                                                "body", Map.of("idempotencyKey", "IDEMP_NEW_20260320_001")),
                                "markSuccessExample", Map.of(
                                                "requestPath",
                                                "/api/examples/payments/orders/PAY_NEW_20260320_001/success",
                                                "body", Map.of(
                                                                "channelTradeNo", "WX202603200099",
                                                                "paidAt", LocalDateTime.of(2026, 3, 20, 16, 30, 0))));
        }

        /**
         * 统一处理教学链路里的非法状态异常。
         *
         * <p>
         * 这里返回 409，表示请求本身不是语法错误，而是和当前订单状态冲突。
         */
        @ExceptionHandler(IllegalStateException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        public Map<String, String> handleIllegalState(IllegalStateException ex) {
                return Map.of("message", ex.getMessage());
        }

        /**
         * 统一处理参数非法问题。
         */
        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Map<String, String> handleIllegalArgument(IllegalArgumentException ex) {
                return Map.of("message", ex.getMessage());
        }

        /**
         * 创建订单请求体。
         */
        public record CreateOrderRequest(
                        String orderNo,
                        String idempotencyKey,
                        Long userId,
                        String productCode,
                        Long amountFen) {
        }

        /**
         * 发起支付请求体。
         */
        public record StartPaymentRequest(String idempotencyKey) {
        }

        /**
         * 回写支付成功请求体。
         */
        public record MarkSuccessRequest(
                        String channelTradeNo,
                        LocalDateTime paidAt) {
        }
}
