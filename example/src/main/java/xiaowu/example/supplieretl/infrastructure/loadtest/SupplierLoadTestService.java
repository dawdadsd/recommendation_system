package xiaowu.example.supplieretl.infrastructure.loadtest;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.example.supplieretl.application.service.SupplierPullExecutionApplicationService;
import xiaowu.example.supplieretl.application.service.SupplierPullExecutionApplicationService.ExecuteCommand;
import xiaowu.example.supplieretl.infrastructure.config.SupplierWorkerProperties;

/**
 * 供应商拉取大规模并发压测核心服务。
 *
 * <h2>设计目标</h2>
 * <ul>
 * <li>1w 级：H2 内存库 + HikariCP(pool=30) + 虚拟线程 → 约 10~30s</li>
 * <li>10w 级：MySQL/PostgreSQL + pool=50 + parallelism=200 → 约 60~120s</li>
 * </ul>
 *
 * <h2>压测流程</h2>
 *
 * <pre>
 *  ① seed(SeedRequest)
 *       └─ JdbcTemplate.batchUpdate() 批量写入 supplier_connection
 *          （每批 500 行，10w 行约 5~10s）
 *
 *  ② run(RunRequest)
 *       ├─ 清理上一轮幂等记录（允许同分钟多次运行）
 *       ├─ 重置 nextPullAt = now()-1s（确保所有供应商立即可调度）
 *       ├─ 查询所有 ACTIVE 压测供应商 ID
 *       └─ 虚拟线程池并发执行全链路：
 *             SupplierPullExecutionApplicationService.execute()
 *               → RoutingSupplierPullClient（KD_/YY_/Generic 三路）
 *               → RawDataPublisher（NoOp 或 Kafka）
 *               → supplier_pull_audit 写审计
 * </pre>
 *
 * <h2>压测供应商 ID 范围</h2>
 * <p>
 * 压测 supplier_id 从 {@link #ID_BASE}（1_000_000）起步，
 * 不与演示种子数据（9001~9203）冲突，cleanup 只清除该范围。
 *
 * <h2>供应商编码规则</h2>
 * <ul>
 * <li>{@code KD_LOAD_*} → RoutingClient → 金蝶沙盒 → 必然成功</li>
 * <li>{@code YY_LOAD_*} → RoutingClient → 用友沙盒 → 必然成功</li>
 * <li>{@code GEN_FAIL_ONCE_*} → MockClient → retryCount==0 时抛异常，
 * 触发执行服务走 UNKNOWN 退避路径，覆盖 markPullFailure 分支</li>
 * <li>{@code GEN_LOAD_*} → MockClient → 正常返回</li>
 * </ul>
 *
 * <h2>10w 级注意事项</h2>
 * <ul>
 * <li>将 {@code spring.datasource.hikari.maximum-pool-size} 设为 50</li>
 * <li>建议 {@code parallelism=200}，避免连接池排队过长</li>
 * <li>虚拟线程（parallelism=0）对 H2 友好，但 100K 虚拟线程占用约 100MB 堆</li>
 * <li>H2 内存库在 10w 条 UPDATE 后响应会变慢，建议切换 MySQL</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierLoadTestService {

  /** 压测供应商 ID 基准值，大于所有演示种子数据 ID。 */
  public static final long ID_BASE = 1_000_000L;

  /** 每批 batchUpdate 的行数：500 行在 H2 下约 5ms。 */
  private static final int SEED_BATCH_SIZE = 500;

  /** 种子 SQL（5 个参数：supplierId, code, nextPullAt, createdAt, updatedAt）。 */
  private static final String SEED_SQL = """
      INSERT INTO supplier_connection (
          supplier_id, supplier_code, status, pull_interval_seconds,
          next_pull_at, last_success_at, last_error_at, last_cursor,
          retry_count, lease_until, version, created_at, updated_at
      ) VALUES (?, ?, 'ACTIVE', 300, ?, NULL, NULL, NULL, 0, NULL, 0, ?, ?)
      """;

  private final JdbcTemplate jdbcTemplate;
  private final SupplierPullExecutionApplicationService executionService;
  private final SupplierWorkerProperties workerProperties;

  // ─── Seed ────────────────────────────────────────────────────────────────────

  /**
   * 批量写入合成供应商连接。
   *
   * @param req 种子配置（供应商数量、ERP 类型比例、失败比例、是否重建）
   * @return 本次写入概要
   */
  public SeedResult seed(SeedRequest req) {
    if (req.reseed()) {
      int deleted = jdbcTemplate.update(
          "DELETE FROM supplier_connection WHERE supplier_id >= ?", ID_BASE);
      log.info("[LoadTest-Seed] cleaned up {} existing load test suppliers", deleted);
    }

    int total = Math.max(1, Math.min(req.supplierCount(), 200_000));
    double kdR = clamp01(req.kdRatio());
    double yyR = Math.min(clamp01(req.yyRatio()), 1.0 - kdR);

    int kdCount = (int) (total * kdR);
    int yyCount = (int) (total * yyR);
    int genCount = total - kdCount - yyCount;
    int failCount = Math.min((int) (total * clamp01(req.failRatio())), genCount);
    int genOkCount = genCount - failCount;

    LocalDateTime now = LocalDateTime.now();
    Timestamp nextPullAt = Timestamp.valueOf(now.minusSeconds(1)); // 立即可调度
    Timestamp nowTs = Timestamp.valueOf(now);

    List<Object[]> rows = new ArrayList<>(total);
    long id = ID_BASE;

    for (int i = 0; i < kdCount; i++, id++) {
      rows.add(new Object[] { id, String.format("KD_LOAD_%07d", i + 1), nextPullAt, nowTs, nowTs });
    }
    for (int i = 0; i < yyCount; i++, id++) {
      rows.add(new Object[] { id, String.format("YY_LOAD_%07d", i + 1), nextPullAt, nowTs, nowTs });
    }
    // 失败供应商：含 FAIL_ONCE 关键字，触发 MockClient 在首次拉取时抛异常
    for (int i = 0; i < failCount; i++, id++) {
      rows.add(new Object[] { id, String.format("GEN_FAIL_ONCE_%07d", i + 1), nextPullAt, nowTs, nowTs });
    }
    for (int i = 0; i < genOkCount; i++, id++) {
      rows.add(new Object[] { id, String.format("GEN_LOAD_%07d", i + 1), nextPullAt, nowTs, nowTs });
    }

    int seeded = 0;
    for (int start = 0; start < rows.size(); start += SEED_BATCH_SIZE) {
      List<Object[]> batch = rows.subList(start, Math.min(start + SEED_BATCH_SIZE, rows.size()));
      jdbcTemplate.batchUpdate(SEED_SQL, batch);
      seeded += batch.size();
      if (seeded % 10_000 == 0 || seeded == rows.size()) {
        log.info("[LoadTest-Seed] progress: {}/{}", seeded, rows.size());
      }
    }

    log.info("[LoadTest-Seed] done: kd={} yy={} genFail={} genOk={} total={}",
        kdCount, yyCount, failCount, genOkCount, seeded);
    return new SeedResult(seeded, kdCount, yyCount, failCount, genOkCount);
  }

  // ─── Run ─────────────────────────────────────────────────────────────────────

  /**
   * 并发执行所有 ACTIVE 压测供应商，驱动完整的拉取→发布→审计链路。
   *
   * <p>
   * <b>并发模型：</b>
   * <ul>
   * <li>{@code parallelism <= 0}：虚拟线程无限制（{@link Executors#newVirtualThreadPerTaskExecutor()}），
   * 每个供应商一个虚拟线程，阻塞等待 DB 连接时自动挂起，不消耗平台线程</li>
   * <li>{@code parallelism > 0}：固定大小的平台线程池，适合精确控制并发 DB 压力</li>
   * </ul>
   *
   * @param req 运行配置
   * @return 压测报告（TPS、P99、成功/失败数等）
   */
  public LoadTestReport run(RunRequest req) throws InterruptedException {
    // ── 1. 清理上一轮幂等记录 ──────────────────────────────────────────────────
    int clearedIdempotency = jdbcTemplate.update(
        "DELETE FROM supplier_pull_idempotency WHERE supplier_id >= ?", ID_BASE);
    log.debug("[LoadTest-Run] cleared {} idempotency records", clearedIdempotency);

    // ── 2. 重置所有 ACTIVE 压测供应商的调度时间 ──────────────────────────────
    jdbcTemplate.update(
        "UPDATE supplier_connection SET next_pull_at = ?, lease_until = NULL " +
            "WHERE supplier_id >= ? AND status = 'ACTIVE'",
        Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)), ID_BASE);

    // ── 3. 加载压测供应商 ID ─────────────────────────────────────────────────
    List<Long> supplierIds = jdbcTemplate.queryForList(
        "SELECT supplier_id FROM supplier_connection " +
            "WHERE supplier_id >= ? AND status = 'ACTIVE' ORDER BY supplier_id",
        Long.class, ID_BASE);

    if (supplierIds.isEmpty()) {
      log.warn("[LoadTest-Run] no ACTIVE suppliers found. Call /seed first.");
      return LoadTestReport.empty("No ACTIVE load test suppliers. Call /seed first.");
    }

    log.info("[LoadTest-Run] starting: {} suppliers, parallelism={}, timeoutSec={}",
        supplierIds.size(), req.parallelism(), req.timeoutSeconds());

    // ── 4. 指标收集 ───────────────────────────────────────────────────────────
    LongAdder successCount = new LongAdder();
    LongAdder failureCount = new LongAdder();
    LongAdder skippedCount = new LongAdder();
    LongAdder totalLatency = new LongAdder();
    AtomicLong maxLatency = new AtomicLong(0);
    // 收集所有延迟用于 P99（100K 时约 800KB，可接受）
    ConcurrentLinkedQueue<Long> latencies = req.collectLatencies()
        ? new ConcurrentLinkedQueue<>()
        : null;

    // ── 5. 线程池 ──────────────────────────────────────────────────────────────
    ExecutorService executor = req.parallelism() <= 0
        ? Executors.newVirtualThreadPerTaskExecutor()
        : Executors.newFixedThreadPool(req.parallelism());

    CountDownLatch latch = new CountDownLatch(supplierIds.size());
    long startMs = System.currentTimeMillis();

    // Worker 参数（避免在 lambda 内多次访问 workerProperties）
    int retryDelay = workerProperties.retryDelaySeconds();
    int backoffBase = workerProperties.retryBackoffBaseSeconds();
    int backoffMax = workerProperties.retryBackoffMaxSeconds();
    int jitterMs = workerProperties.retryBackoffMaxJitterMs();

    for (Long supplierId : supplierIds) {
      executor.submit(() -> {
        long t0 = System.currentTimeMillis();
        try {
          var result = executionService.execute(new ExecuteCommand(
              supplierId, retryDelay, backoffBase, backoffMax, jitterMs));

          if (result.success()) {
            // 幂等跳过也算 success=true，通过 errorMessage 区分
            if (result.errorMessage() != null
                && result.errorMessage().startsWith("Skipped:")) {
              skippedCount.increment();
            } else {
              successCount.increment();
            }
          } else {
            failureCount.increment();
          }

        } catch (Exception ex) {
          // 熔断快速失败 / 并发拒绝 等也计入失败
          failureCount.increment();
          log.debug("[LoadTest-Run] supplierId={} threw: {}", supplierId, ex.getMessage());
        } finally {
          long latencyMs = System.currentTimeMillis() - t0;
          totalLatency.add(latencyMs);
          maxLatency.updateAndGet(cur -> Math.max(cur, latencyMs));
          if (latencies != null)
            latencies.add(latencyMs);
          latch.countDown();
        }
      });
    }

    int timeoutSec = req.timeoutSeconds() <= 0 ? 600 : req.timeoutSeconds();
    boolean completed = latch.await(timeoutSec, TimeUnit.SECONDS);
    executor.shutdownNow();

    long durationMs = System.currentTimeMillis() - startMs;
    long executed = successCount.sum() + failureCount.sum() + skippedCount.sum();
    double avgLatMs = executed > 0 ? (double) totalLatency.sum() / executed : 0;
    double tps = durationMs > 0 ? executed * 1000.0 / durationMs : 0;

    // P99 计算
    long p99Ms = 0;
    if (latencies != null && !latencies.isEmpty()) {
      List<Long> sorted = latencies.stream().sorted().toList();
      p99Ms = sorted.get((int) (sorted.size() * 0.99));
    }

    log.info("[LoadTest-Run] done: executed={} success={} fail={} skipped={} tps={} durationMs={}",
        executed, successCount.sum(), failureCount.sum(), skippedCount.sum(),
        String.format("%.1f", tps), durationMs);

    return new LoadTestReport(
        supplierIds.size(), executed,
        successCount.sum(), failureCount.sum(), skippedCount.sum(),
        Math.round(avgLatMs * 10.0) / 10.0,
        maxLatency.get(), p99Ms,
        Math.round(tps * 10.0) / 10.0,
        durationMs,
        completed ? "COMPLETED" : "TIMEOUT");
  }

  // ─── Status / Cleanup ────────────────────────────────────────────────────────

  /**
   * 返回当前压测供应商数量和相关审计记录数，用于监控压测进度。
   */
  public LoadTestStatus status() {
    long total = queryCount("SELECT COUNT(*) FROM supplier_connection WHERE supplier_id >= ?");
    long active = queryCount("SELECT COUNT(*) FROM supplier_connection WHERE supplier_id >= ? AND status = 'ACTIVE'");
    long suspend = queryCount(
        "SELECT COUNT(*) FROM supplier_connection WHERE supplier_id >= ? AND status = 'SUSPENDED'");
    long idemp = queryCount("SELECT COUNT(*) FROM supplier_pull_idempotency WHERE supplier_id >= ?");
    long audit = queryCount("SELECT COUNT(*) FROM supplier_pull_audit WHERE supplier_id >= ?");
    return new LoadTestStatus(total, active, suspend, idemp, audit);
  }

  /**
   * 删除所有压测数据（supplier_connection / idempotency / audit 三张表）。
   *
   * @return 删除的 supplier_connection 行数
   */
  public int cleanup() {
    jdbcTemplate.update("DELETE FROM supplier_pull_audit WHERE supplier_id >= ?", ID_BASE);
    jdbcTemplate.update("DELETE FROM supplier_pull_idempotency WHERE supplier_id >= ?", ID_BASE);
    int deleted = jdbcTemplate.update(
        "DELETE FROM supplier_connection WHERE supplier_id >= ?", ID_BASE);
    log.info("[LoadTest-Cleanup] deleted {} supplier_connection rows", deleted);
    return deleted;
  }

  // ─── 工具 ─────────────────────────────────────────────────────────────────────

  private long queryCount(String sql) {
    Long v = jdbcTemplate.queryForObject(sql, Long.class, ID_BASE);
    return v != null ? v : 0L;
  }

  private static double clamp01(double v) {
    return Math.max(0.0, Math.min(1.0, v));
  }

  // ─── 请求 / 响应数据模型 ───────────────────────────────────────────────────────

  /**
   * @param supplierCount 要写入的供应商总数（上限 200,000）
   * @param kdRatio       KD_（金蝶）比例，[0,1]，默认 0.30
   * @param yyRatio       YY_（用友）比例，[0,1]，默认 0.30
   * @param failRatio     模拟失败比例（在 GEN_ 供应商里），默认 0.05
   * @param reseed        true = 先清除已有压测数据再写入
   */
  public record SeedRequest(
      int supplierCount,
      double kdRatio,
      double yyRatio,
      double failRatio,
      boolean reseed) {
  }

  /**
   * @param totalSeeded      实际写入行数
   * @param kingdeeCount     金蝶供应商数
   * @param yonyouCount      用友供应商数
   * @param genericFailCount 配置为首次失败的通用供应商数
   * @param genericOkCount   正常通用供应商数
   */
  public record SeedResult(
      int totalSeeded,
      int kingdeeCount,
      int yonyouCount,
      int genericFailCount,
      int genericOkCount) {
  }

  /**
   * @param parallelism      并发度：≤0 = 虚拟线程无限制；>0 = 固定线程池
   * @param timeoutSeconds   最长等待秒数（≤0 默认 600s）
   * @param collectLatencies true 时收集每条延迟用于 P99 计算（100K 时额外占 ~800KB）
   */
  public record RunRequest(
      int parallelism,
      int timeoutSeconds,
      boolean collectLatencies) {
  }

  /**
   * 压测运行报告。
   *
   * @param totalActiveSuppliers  参与本轮压测的 ACTIVE 供应商数
   * @param executedCount         实际完成的执行次数（含成功+失败+跳过）
   * @param successCount          成功拉取数
   * @param failureCount          失败数（含所有失败类型）
   * @param skippedDuplicateCount 幂等跳过数
   * @param avgLatencyMs          平均单次执行耗时（ms）
   * @param maxLatencyMs          最大单次执行耗时（ms）
   * @param p99LatencyMs          P99 延迟（ms），仅 collectLatencies=true 时有效
   * @param throughputPerSec      整体 TPS（executions/sec）
   * @param totalDurationMs       整轮压测耗时（ms）
   * @param status                COMPLETED / TIMEOUT
   */
  public record LoadTestReport(
      long totalActiveSuppliers,
      long executedCount,
      long successCount,
      long failureCount,
      long skippedDuplicateCount,
      double avgLatencyMs,
      long maxLatencyMs,
      long p99LatencyMs,
      double throughputPerSec,
      long totalDurationMs,
      String status) {

    static LoadTestReport empty(String reason) {
      return new LoadTestReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, reason);
    }
  }

  /**
   * 压测数据现状快照。
   */
  public record LoadTestStatus(
      long totalLoadTestSuppliers,
      long activeCount,
      long suspendedCount,
      long idempotencyRecords,
      long auditRecords) {
  }
}
