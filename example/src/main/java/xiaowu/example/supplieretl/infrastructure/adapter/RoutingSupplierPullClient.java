package xiaowu.example.supplieretl.infrastructure.adapter;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import xiaowu.example.supplieretl.application.port.SupplierFetchException;
import xiaowu.example.supplieretl.application.port.SupplierPullClient;
import xiaowu.example.supplieretl.infrastructure.adapter.kingdee.KingdeeErpAdapter;
import xiaowu.example.supplieretl.infrastructure.adapter.yonyou.YonyouErpAdapter;
import xiaowu.example.supplieretl.infrastructure.resilience.SupplierCircuitBreaker;

/**
 * 按 supplierCode 前缀路由到对应 ERP 适配器，同时内置各 ERP 类型的独立熔断器。
 *
 * <h2>路由规则</h2>
 *
 * <pre>
 *   KD_*  →  KingdeeErpAdapter （金蝶云星空）
 *   YY_*  →  YonyouErpAdapter  （用友 BIP）
 *   其余   →  fallback（MockSupplierPullClient）
 * </pre>
 *
 * <h2>熔断器隔离</h2>
 * <p>
 * 金蝶和用友各自持有一个独立的 {@link SupplierCircuitBreaker}，
 * 避免某一 ERP 整体不可用时影响另一 ERP 的正常拉取。
 *
 * <h2>为什么不用 Resilience4J</h2>
 * <p>
 * 本模块是教学示例，手写三态熔断器 {@link SupplierCircuitBreaker} 让读者
 * 不依赖黑盒框架就能看懂熔断的核心状态机，可直接替换为 Resilience4J。
 */
@Slf4j
public class RoutingSupplierPullClient implements SupplierPullClient {

  private static final String PREFIX_KINGDEE = "KD_";
  private static final String PREFIX_YONYOU = "YY_";

  private final KingdeeErpAdapter kingdeeAdapter;
  private final YonyouErpAdapter yonyouAdapter;
  private final SupplierPullClient fallbackClient;

  /** ERP 类型级别的熔断器（不是每个供应商一个）。 */
  private final SupplierCircuitBreaker kingdeeCb;
  private final SupplierCircuitBreaker yonyouCb;

  /**
   * Per-supplier 并发令牌桶：每个 supplierId 最多允许 1 个并发拉取。
   * 使用 ConcurrentHashMap + putIfAbsent 实现"锁槽"语义，
   * 防止同一供应商被 Worker 集群内多个线程同时拉取（幂等兜底）。
   */
  private final ConcurrentHashMap<Long, Boolean> inFlight = new ConcurrentHashMap<>();

  public RoutingSupplierPullClient(
      KingdeeErpAdapter kingdeeAdapter,
      YonyouErpAdapter yonyouAdapter,
      SupplierPullClient fallbackClient,
      Map<String, Object> cbConfig) {

    this.kingdeeAdapter = kingdeeAdapter;
    this.yonyouAdapter = yonyouAdapter;
    this.fallbackClient = fallbackClient;

    int threshold = getInt(cbConfig, "failureThreshold", 5);
    long windowMs = getLong(cbConfig, "openWindowMs", 60_000L);
    Duration window = Duration.ofMillis(windowMs);

    this.kingdeeCb = new SupplierCircuitBreaker("kingdee-cb", threshold, window);
    this.yonyouCb = new SupplierCircuitBreaker("yonyou-cb", threshold, window);
  }

  // ─── SupplierPullClient 实现 ──────────────────────────────────────────────────

  @Override
  public PullResult pull(PullCommand command) {
    // ── 每供应商并发隔离 ──────────────────────────────────────────────────────
    if (inFlight.putIfAbsent(command.supplierId(), Boolean.TRUE) != null) {
      throw new IllegalStateException(
          "Concurrent pull rejected for supplierId=" + command.supplierId()
              + ": another pull is already in flight");
    }

    try {
      return doPull(command);
    } finally {
      inFlight.remove(command.supplierId());
    }
  }

  // ─── 内部路由 + 熔断 ──────────────────────────────────────────────────────────

  private PullResult doPull(PullCommand command) {
    String code = command.supplierCode();

    if (code.startsWith(PREFIX_KINGDEE)) {
      return executeWithCircuitBreaker(kingdeeCb, command, kingdeeAdapter);
    }
    if (code.startsWith(PREFIX_YONYOU)) {
      return executeWithCircuitBreaker(yonyouCb, command, yonyouAdapter);
    }

    log.debug("[Router] supplierId={} code={} → fallback", command.supplierId(), code);
    return fallbackClient.pull(command);
  }

  private PullResult executeWithCircuitBreaker(
      SupplierCircuitBreaker cb,
      PullCommand command,
      SupplierPullClient adapter) {
    try {
      return cb.executeChecked(() -> adapter.pull(command));
    } catch (SupplierCircuitBreaker.CircuitOpenException ex) {
      // 熔断 → 转换为 UNAVAILABLE，让执行服务按退避策略重试
      throw new SupplierFetchException.UnavailableException(
          command.supplierId(), 503, "Circuit open: " + ex.getMessage());
    } catch (SupplierFetchException ex) {
      throw ex; // 已分类，直接透传
    } catch (Exception ex) {
      throw new SupplierFetchException.TimeoutException(
          command.supplierId(),
          "Unexpected error in adapter: " + ex.getMessage(), ex);
    }
  }

  // ─── 工具方法 ─────────────────────────────────────────────────────────────────

  private static int getInt(Map<String, Object> m, String key, int def) {
    Object v = m.get(key);
    if (v instanceof Number n)
      return n.intValue();
    return def;
  }

  private static long getLong(Map<String, Object> m, String key, long def) {
    Object v = m.get(key);
    if (v instanceof Number n)
      return n.longValue();
    return def;
  }
}
