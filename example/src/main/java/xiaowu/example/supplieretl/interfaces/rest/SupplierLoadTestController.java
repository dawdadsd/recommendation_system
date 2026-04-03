package xiaowu.example.supplieretl.interfaces.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.example.supplieretl.infrastructure.loadtest.SupplierLoadTestService;
import xiaowu.example.supplieretl.infrastructure.loadtest.SupplierLoadTestService.LoadTestReport;
import xiaowu.example.supplieretl.infrastructure.loadtest.SupplierLoadTestService.LoadTestStatus;
import xiaowu.example.supplieretl.infrastructure.loadtest.SupplierLoadTestService.RunRequest;
import xiaowu.example.supplieretl.infrastructure.loadtest.SupplierLoadTestService.SeedRequest;
import xiaowu.example.supplieretl.infrastructure.loadtest.SupplierLoadTestService.SeedResult;

/**
 * 供应商拉取大规模并发压测 REST API。
 *
 * <h2>推荐测试流程（Swagger UI / curl）</h2>
 * 
 * <pre>
 * # ① 写入 1万 合成供应商（KD_/YY_/通用）
 * POST /api/examples/suppliers/load-test/seed
 * {
 *   "supplierCount": 10000,
 *   "kdRatio": 0.30,       // 3000 金蝶沙盒
 *   "yyRatio": 0.30,       // 3000 用友沙盒
 *   "failRatio": 0.05,     // 200 首次失败（覆盖退避分支）
 *   "reseed": true
 * }
 *
 * # ② 并发执行全链路（虚拟线程，无限并发度）
 * POST /api/examples/suppliers/load-test/run
 * {
 *   "parallelism": 0,      // 0 = 虚拟线程；>0 = 固定线程池
 *   "timeoutSeconds": 120,
 *   "collectLatencies": true  // 收集 P99
 * }
 *
 * # ③ 查看现状
 * GET /api/examples/suppliers/load-test/status
 *
 * # ④ 清理压测数据（不影响演示种子数据 9001-9203）
 * DELETE /api/examples/suppliers/load-test
 * </pre>
 *
 * <h2>10万级压测建议</h2>
 * <ul>
 * <li>将 {@code spring.datasource.hikari.maximum-pool-size} 调至 50</li>
 * <li>使用 {@code parallelism: 200}（固定平台线程池），避免 H2 连接争用</li>
 * <li>H2 内存库在 10w 场景下约 60~120s；切换 MySQL 可缩短到 30s</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/examples/suppliers/load-test")
@RequiredArgsConstructor
@Tag(name = "Load Test", description = "供应商拉取大规模并发压测（1w ~ 10w 级）")
public class SupplierLoadTestController {

  private final SupplierLoadTestService loadTestService;

  // ─── Status ──────────────────────────────────────────────────────────────────

  @GetMapping("/status")
  @Operation(summary = "压测数据快照", description = "返回当前压测供应商数量、活跃/挂起数量及相关审计记录数")
  public ResponseEntity<LoadTestStatus> status() {
    return ResponseEntity.ok(loadTestService.status());
  }

  // ─── Seed ────────────────────────────────────────────────────────────────────

  @PostMapping("/seed")
  @Operation(summary = "批量写入合成供应商", description = "按比例生成 KD_/YY_/通用 供应商记录并落库（batchUpdate），ID 从 1,000,000 起，不影响已有演示数据")
  public ResponseEntity<SeedResult> seed(
      @RequestBody @Parameter(description = "种子配置") SeedRequest req) {
    log.info("[LoadTest-API] seed requested: count={} kd={} yy={} fail={} reseed={}",
        req.supplierCount(), req.kdRatio(), req.yyRatio(), req.failRatio(), req.reseed());
    return ResponseEntity.ok(loadTestService.seed(req));
  }

  // ─── Run ─────────────────────────────────────────────────────────────────────

  @PostMapping("/run")
  @Operation(summary = "并发执行压测", description = "对所有 ACTIVE 压测供应商驱动完整拉取链路（ERP 沙盒→Kafka→审计），返回 TPS/P99 报告。"
      + "parallelism=0 使用虚拟线程（推荐 I/O 密集），>0 使用固定平台线程池。")
  public ResponseEntity<LoadTestReport> run(
      @RequestBody @Parameter(description = "运行配置") RunRequest req)
      throws InterruptedException {
    log.info("[LoadTest-API] run requested: parallelism={} timeout={}s collectLatencies={}",
        req.parallelism(), req.timeoutSeconds(), req.collectLatencies());
    return ResponseEntity.ok(loadTestService.run(req));
  }

  // ─── Seed + Run ──────────────────────────────────────────────────────────────

  /**
   * 一步完成：写入 + 执行（适合 CI 环境快速端到端验证）。
   */
  @PostMapping("/seed-and-run")
  @Operation(summary = "写入并立即运行", description = "先 seed（reseed=true）再 run，适合一键完整走通所有链路")
  public ResponseEntity<LoadTestReport> seedAndRun(
      @RequestBody @Parameter(description = "组合配置") SeedAndRunRequest req)
      throws InterruptedException {
    log.info("[LoadTest-API] seed-and-run: count={}", req.seed().supplierCount());
    loadTestService.seed(req.seed());
    return ResponseEntity.ok(loadTestService.run(req.run()));
  }

  // ─── Cleanup ─────────────────────────────────────────────────────────────────

  @DeleteMapping
  @Operation(summary = "清理压测数据", description = "删除 supplier_id >= 1,000,000 的全部 supplier_connection / idempotency / audit 记录，演示种子数据（9001-9203）不受影响")
  public ResponseEntity<CleanupResult> cleanup() {
    int deleted = loadTestService.cleanup();
    log.info("[LoadTest-API] cleanup done: {} rows deleted", deleted);
    return ResponseEntity.ok(new CleanupResult(deleted,
        "Deleted all load test suppliers (id >= 1,000,000). Demo seed data intact."));
  }

  // ─── 组合请求/响应模型 ────────────────────────────────────────────────────────

  /**
   * seed-and-run 接口请求体。
   */
  public record SeedAndRunRequest(SeedRequest seed, RunRequest run) {
  }

  /**
   * cleanup 响应体。
   */
  public record CleanupResult(int deletedSuppliers, String message) {
  }
}
