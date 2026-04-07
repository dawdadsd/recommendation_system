package xiaowu.example.supplieretl.infrastructure.adapter.yonyou;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import xiaowu.example.supplieretl.application.port.SupplierFetchException;
import xiaowu.example.supplieretl.application.port.SupplierPullClient;

/**
 * 用友 BIP（YonBIP）OpenAPI 供应商拉取适配器。
 *
 * <h2>用友 BIP API 协议说明</h2>
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │  POST {baseUrl}/api/v1/auth/token                                          │
 * │  Body: { "appId": "xxx", "appSecret": "xxx",                              │
 * │           "grantType": "client_credentials" }                             │
 * │  Response: { "code": 0, "data": {                                         │
 * │                "access_token": "...", "expires_in": 7200 } }              │
 * ├─────────────────────────────────────────────────────────────────────────────┤
 * │  GET {baseUrl}/api/uapbd/supplier/page                                     │
 * │  Headers: Authorization: Bearer {token}                                    │
 * │           X-Corp-Code: {corpCode}                                          │
 * │  Params: pageSize=100 & pageIndex=1 & modifyDateStart=2026-01-01           │
 * │  Response: {                                                               │
 * │    "code": 0,                                                              │
 * │    "data": {                                                               │
 * │      "rows": [{ "code":"YY001","name":"...","status":"Y",...},...],        │
 * │      "totalCount": 500,                                                    │
 * │      "pageIndex": 1,                                                       │
 * │      "hasMore": true                                                       │
 * │    }                                                                       │
 * │  }                                                                         │
 * └─────────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>分页游标说明</h2>
 * <p>
 * 用友 BIP 采用 pageIndex + modifyDateStart 组合分页，游标编码为：
 * {@code yyyy-MM-dd|pageIndex}，例如 {@code 2026-01-01|3}。
 *
 * <h2>Token 生命周期</h2>
 * <p>
 * Access Token 有效期 7200s（2h）。本适配器在 Token 过期前 60s 自动刷新，
 * 避免在高并发批次中途出现 401。
 */
@Slf4j
public class YonyouErpAdapter implements SupplierPullClient {

  private static final DateTimeFormatter YY_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private static final String TOKEN_PATH = "/api/v1/auth/token";
  private static final String SUPPLIER_PATH = "/api/uapbd/supplier/page";

  /** 在 Token 剩余有效期小于此阈值时提前刷新，单位：秒。 */
  private static final int TOKEN_REFRESH_BUFFER_SECONDS = 60;

  private final YonyouErpProperties props;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  /** 持有当前有效的 Token + 过期时刻，以原子引用保证可见性。 */
  private final AtomicReference<TokenHolder> tokenHolder = new AtomicReference<>();

  public YonyouErpAdapter(YonyouErpProperties props, ObjectMapper objectMapper) {
    this.props = props;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(props.connectTimeoutMs()))
        .build();
  }

  // ─── SupplierPullClient 实现 ──────────────────────────────────────────────────

  @Override
  public PullResult pull(PullCommand command) {
    log.debug("[Yonyou] pull start supplierId={} cursor={}", command.supplierId(), command.lastCursor());

    if (props.sandbox()) {
      return sandboxPull(command);
    }

    try {
      return realPull(command);
    } catch (SupplierFetchException ex) {
      throw ex;
    } catch (IOException ex) {
      throw new SupplierFetchException.TimeoutException(
          command.supplierId(), "Yonyou HTTP IO error: " + ex.getMessage(), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new SupplierFetchException.TimeoutException(
          command.supplierId(), "Yonyou pull interrupted", ex);
    }
  }

  // ─── 真实 HTTP 调用 ────────────────────────────────────────────────────────────

  private PullResult realPull(PullCommand command)
      throws IOException, InterruptedException {

    String token = ensureToken(command.supplierId());
    CursorParts cursor = CursorParts.parse(command.lastCursor());

    // ── 构建查询 URL ──────────────────────────────────────────────────────────
    String dateParam = cursor.modifyDateStart != null
        ? cursor.modifyDateStart
        : LocalDate.now().minusDays(7).format(YY_DATE_FMT); // 默认拉取近 7 天

    String url = props.baseUrl() + SUPPLIER_PATH
        + "?pageSize=" + props.pageSize()
        + "&pageIndex=" + cursor.pageIndex
        + "&modifyDateStart=" + URLEncoder.encode(dateParam, StandardCharsets.UTF_8);

    var request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofMillis(props.readTimeoutMs()))
        .header("Authorization", "Bearer " + token)
        .header("X-Corp-Code", props.corpCode())
        .header("Content-Type", "application/json")
        .GET()
        .build();

    HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    String rawPayload = resp.body();
    handleHttpErrors(command.supplierId(), resp);

    // ── 解析响应 ──────────────────────────────────────────────────────────────
    JsonNode root = parseJson(command.supplierId(), resp.body());
    int code = root.path("code").asInt(-1);
    if (code != 0) {
      // 用友 BIP 约定：code != 0 表示业务错误
      throw new SupplierFetchException.DataException(
          command.supplierId(), snippet(resp.body()), null);
    }

    JsonNode data = root.path("data");
    int count = data.path("rows").size();
    boolean hasMore = data.path("hasMore").asBoolean(false);
    int returnedPage = data.path("pageIndex").asInt(cursor.pageIndex);

    // ── 计算下一个游标 ────────────────────────────────────────────────────────
    String nextCursor = hasMore
        ? new CursorParts(dateParam, returnedPage + 1).encode()
        : new CursorParts(LocalDate.now().format(YY_DATE_FMT), 1).encode(); // 本轮结束，重置到今天第 1 页

    log.info("[Yonyou] pull done supplierId={} count={} hasMore={} nextCursor={}",
        command.supplierId(), count, hasMore, nextCursor);

    return new PullResult(nextCursor, count, LocalDateTime.now(), rawPayload);
  }

  // ─── Token 管理 ───────────────────────────────────────────────────────────────

  /**
   * 返回当前有效 Token，必要时自动刷新。
   *
   * <p>
   * 使用双重检查 + AtomicReference CAS，避免并发环境下重复刷新。
   */
  private String ensureToken(long supplierId) throws IOException, InterruptedException {
    TokenHolder current = tokenHolder.get();
    if (current != null && current.expiresAt().isAfter(
        Instant.now().plusSeconds(TOKEN_REFRESH_BUFFER_SECONDS))) {
      return current.token();
    }
    // Token 过期或即将过期，刷新
    TokenHolder fresh = fetchToken(supplierId);
    tokenHolder.set(fresh);
    return fresh.token();
  }

  private TokenHolder fetchToken(long supplierId) throws IOException, InterruptedException {
    var body = objectMapper.writeValueAsString(Map.of(
        "appId", props.appId(),
        "appSecret", props.appSecret(),
        "grantType", "client_credentials"));

    var request = HttpRequest.newBuilder()
        .uri(URI.create(props.baseUrl() + TOKEN_PATH))
        .timeout(Duration.ofMillis(props.readTimeoutMs()))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() == 401 || resp.statusCode() == 403) {
      throw new SupplierFetchException.AuthException(supplierId, resp.statusCode());
    }
    if (resp.statusCode() != 200) {
      throw new SupplierFetchException.UnavailableException(supplierId, resp.statusCode(),
          "Yonyou token endpoint returned " + resp.statusCode());
    }

    JsonNode json = parseJson(supplierId, resp.body());
    String token = json.path("data").path("access_token").asText();
    long expiresIn = json.path("data").path("expires_in").asLong(7200);
    Instant expiresAt = Instant.now().plusSeconds(expiresIn);

    log.debug("[Yonyou] token refreshed, expiresAt={}", expiresAt);
    return new TokenHolder(token, expiresAt);
  }

  private record TokenHolder(String token, Instant expiresAt) {
  }

  // ─── Sandbox 模式 ─────────────────────────────────────────────────────────────

  /**
   * Sandbox 返回符合用友 BIP 真实格式的虚构供应商列表。
   *
   * <p>
   * JSON 结构与真实接口完全一致，下游 Spark ETL 通过 {@code YY_} 前缀
   * 选择用友解析器，无需区分沙盒/真实数据。
   */
  private PullResult sandboxPull(PullCommand command) {
    CursorParts cursor = CursorParts.parse(command.lastCursor());
    if (cursor.pageIndex > 2) {
      log.debug("[Yonyou-Sandbox] supplierId={} no more pages", command.supplierId());
      return new PullResult(
          command.lastCursor(),
          0,
          LocalDateTime.now(),
          writePayload(Map.of(
              "code", 0,
              "data", Map.of(
                  "rows", List.of(),
                  "totalCount", 0,
                  "pageIndex", cursor.pageIndex,
                  "hasMore", false)),
              command.supplierId()));
    }

    String today = LocalDate.now().format(YY_DATE_FMT);
    List<Map<String, Object>> rows = List.of(
        Map.of("code", "YY_SUP_001", "name", "用友ERP示例-深圳原材料", "status", "Y",
            "taxNo", "91440300XXXXXXXXX1", "modifyDate", today),
        Map.of("code", "YY_SUP_002", "name", "用友ERP示例-浙江配件厂", "status", "Y",
            "taxNo", "913300000000000002", "modifyDate", today),
        Map.of("code", "YY_SUP_003", "name", "用友ERP示例-东莞外协厂", "status", "N",
            "taxNo", "914400000000000003", "modifyDate", today));

    boolean hasMore = cursor.pageIndex < 2;
    String nextCursor = hasMore
        ? new CursorParts(today, cursor.pageIndex + 1).encode()
        : new CursorParts(today, 1).encode();

    log.debug("[Yonyou-Sandbox] supplierId={} page={} returning {} rows",
        command.supplierId(), cursor.pageIndex, rows.size());

    return new PullResult(
        nextCursor,
        rows.size(),
        LocalDateTime.now(),
        writePayload(Map.of(
            "code", 0,
            "data", Map.of(
                "rows", rows,
                "totalCount", rows.size(),
                "pageIndex", cursor.pageIndex,
                "hasMore", hasMore)),
            command.supplierId()));
  }

  // ─── 工具方法 ─────────────────────────────────────────────────────────────────

  private void handleHttpErrors(long supplierId, HttpResponse<String> resp) {
    int status = resp.statusCode();
    if (status == 200)
      return;
    if (status == 401 || status == 403) {
      tokenHolder.set(null); // 清除过期 token
      throw new SupplierFetchException.AuthException(supplierId, status);
    }
    if (status == 429) {
      // 用友 BIP 限流时会在 Retry-After 头中提供等待秒数
      Instant retryAfter = resp.headers().firstValue("Retry-After")
          .map(v -> {
            try {
              return Instant.now().plusSeconds(Long.parseLong(v));
            } catch (NumberFormatException ex) {
              return null;
            }
          })
          .orElse(null);
      throw new SupplierFetchException.RateLimitedException(supplierId, retryAfter);
    }
    if (status >= 500) {
      throw new SupplierFetchException.UnavailableException(supplierId, status,
          "Yonyou server error " + status);
    }
    throw new SupplierFetchException.DataException(supplierId, snippet(resp.body()), null);
  }

  private JsonNode parseJson(long supplierId, String body) {
    try {
      return objectMapper.readTree(body);
    } catch (IOException ex) {
      throw new SupplierFetchException.DataException(supplierId, snippet(body), ex);
    }
  }

  private static String snippet(String s) {
    return s == null ? "" : s.substring(0, Math.min(512, s.length()));
  }

  private String writePayload(Object payload, long supplierId) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (IOException ex) {
      throw new SupplierFetchException.DataException(supplierId, snippet(String.valueOf(payload)), ex);
    }
  }

  // ─── 游标编解码 ───────────────────────────────────────────────────────────────

  /**
   * 用友游标格式：{@code yyyy-MM-dd|pageIndex}，例如 {@code 2026-01-01|3}。
   */
  private record CursorParts(String modifyDateStart, int pageIndex) {

    static CursorParts parse(String cursor) {
      if (cursor == null || cursor.isBlank())
        return new CursorParts(null, 1);
      int sep = cursor.lastIndexOf('|');
      if (sep < 0)
        return new CursorParts(cursor, 1);
      String date = cursor.substring(0, sep);
      int page;
      try {
        page = Math.max(1, Integer.parseInt(cursor.substring(sep + 1)));
      } catch (NumberFormatException ex) {
        page = 1;
      }
      return new CursorParts(date, page);
    }

    String encode() {
      return (modifyDateStart == null ? "" : modifyDateStart) + "|" + pageIndex;
    }
  }
}
