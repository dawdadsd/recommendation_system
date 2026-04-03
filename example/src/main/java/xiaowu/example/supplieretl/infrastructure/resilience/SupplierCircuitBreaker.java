package xiaowu.example.supplieretl.infrastructure.resilience;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;

/**
 * 针对单个 ERP 系统类型（金蝶 / 用友）的轻量级熔断器。
 *
 * <h2>状态机</h2>
 *
 * <pre>
 *                 failureCount >= threshold
 *   ┌──────────┐ ─────────────────────────── ┌──────────┐
 *   │  CLOSED  │                             │   OPEN   │
 *   │（正常）  │ ◄───────────────────────── │（快速失败）│
 *   └──────────┘  成功一次                  └──────────┘
 *                                                │
 *                                 等待 openWindowMs 后
 *                                                │
 *                                                ▼
 *                                         ┌──────────┐
 *                                         │HALF_OPEN │
 *                                         │（探针中） │
 *                                         └──────────┘
 * </pre>
 *
 * <h2>设计原则</h2>
 * <ul>
 * <li>不引入任何外部库，仅用 JUC 原子变量实现，方便作为教学样例理解原理</li>
 * <li>HALF_OPEN 阶段只允许 1 个请求通过（探针请求），其余快速失败</li>
 * <li>失败计数采用滑动窗口语义近似：每次成功将计数清零，避免旧失败永久累积</li>
 * </ul>
 */
@Slf4j
public class SupplierCircuitBreaker {

  public enum State {
    CLOSED, OPEN, HALF_OPEN
  }

  private final String name;
  private final int failureThreshold; // 连续失败多少次后打开熔断
  private final long openWindowMs; // OPEN 状态持续时长（ms），之后转 HALF_OPEN

  private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
  private final AtomicInteger failureCount = new AtomicInteger(0);
  private final AtomicLong openSince = new AtomicLong(0);
  /** HALF_OPEN 探针槽：true = 探针请求已在飞行中，其余请求快速失败 */
  private final AtomicReference<Boolean> probeInFlight = new AtomicReference<>(false);

  public SupplierCircuitBreaker(String name, int failureThreshold, Duration openWindow) {
    this.name = name;
    this.failureThreshold = failureThreshold;
    this.openWindowMs = openWindow.toMillis();
  }

  /**
   * 执行一次受保护的调用。
   *
   * @param <T>      返回值类型
   * @param supplier 实际调用逻辑
   * @return 调用结果
   * @throws CircuitOpenException 熔断器打开 / 探针占满时快速失败
   */
  public <T> T executeChecked(java.util.concurrent.Callable<T> supplier) throws Exception {
    checkState();
    try {
      T result = supplier.call();
      onSuccess();
      return result;
    } catch (Exception ex) {
      onFailure();
      throw ex;
    }
  }

  /** 当前熔断器状态（外部监控用）。 */
  public State currentState() {
    maybeTransitionToHalfOpen();
    return state.get();
  }

  /** 外部监控：连续失败次数。 */
  public int failureCount() {
    return failureCount.get();
  }

  // ─── 状态转换 ─────────────────────────────────────────────────────────────────

  private void checkState() {
    maybeTransitionToHalfOpen();
    State s = state.get();
    if (s == State.CLOSED)
      return;

    if (s == State.OPEN) {
      throw new CircuitOpenException(name + " circuit is OPEN");
    }
    // HALF_OPEN：只允许一个探针请求通过
    if (!probeInFlight.compareAndSet(false, true)) {
      throw new CircuitOpenException(name + " circuit is HALF_OPEN, probe in flight");
    }
    log.info("[CircuitBreaker] {} HALF_OPEN probe allowed", name);
  }

  private void maybeTransitionToHalfOpen() {
    if (state.get() == State.OPEN) {
      long elapsed = System.currentTimeMillis() - openSince.get();
      if (elapsed >= openWindowMs) {
        if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
          probeInFlight.set(false);
          log.info("[CircuitBreaker] {} OPEN → HALF_OPEN after {}ms", name, elapsed);
        }
      }
    }
  }

  private void onSuccess() {
    State s = state.get();
    if (s == State.HALF_OPEN) {
      // 探针成功 → 关闭熔断
      if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
        failureCount.set(0);
        probeInFlight.set(false);
        log.info("[CircuitBreaker] {} HALF_OPEN → CLOSED (probe succeeded)", name);
      }
    } else {
      failureCount.set(0);
    }
  }

  private void onFailure() {
    State s = state.get();
    if (s == State.HALF_OPEN) {
      // 探针失败 → 重新打开熔断
      if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
        openSince.set(System.currentTimeMillis());
        probeInFlight.set(false);
        log.warn("[CircuitBreaker] {} HALF_OPEN → OPEN (probe failed)", name);
      }
      return;
    }
    int count = failureCount.incrementAndGet();
    if (count >= failureThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
      openSince.set(System.currentTimeMillis());
      log.warn("[CircuitBreaker] {} CLOSED → OPEN (failures={})", name, count);
    }
  }

  // ─── 快速失败异常 ─────────────────────────────────────────────────────────────

  /** 熔断器打开或 HALF_OPEN 槽被占时抛出，调用方无需重试（等待熔断窗口结束）。 */
  public static final class CircuitOpenException extends RuntimeException {
    public CircuitOpenException(String message) {
      super(message);
    }
  }
}
