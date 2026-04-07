package xiaowu.example.supplieretl.infrastructure.adapter.kingdee;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import lombok.extern.slf4j.Slf4j;
import xiaowu.example.supplieretl.application.port.SupplierFetchException;
import xiaowu.example.supplieretl.application.port.SupplierPullClient;

/**
 * 金蝶云星空（Kingdee Cloud S）供应商拉取适配器。
 *
 * <h2>金蝶星空 API 协议说明</h2>
 * <p>
 * 金蝶星空的 Web API 采用统一的"服务存根"路径风格，所有调用都命中同一个端点前缀，
 * 通过 HTTP POST + JSON Body 区分不同操作：
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │  POST {baseUrl}/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService         │
 * │       .Auth.common.kdsvc                                                   │
 * │  Body: { "acctID": "...", "username": "...", "password": "md5(pwd)",       │
 * │           "lcid": 2052 }                                                   │
 * │  Response: { "LoginResultType": 1, "Context": {...},                       │
 * │              "KDSVCSessionId": "session_uuid" }                            │
 * ├─────────────────────────────────────────────────────────────────────────────┤
 * │  POST {baseUrl}/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService         │
 * │       .ExecuteBillQuery.common.kdsvc                                       │
 * │  Body: {                                                                   │
 * │    "FormId":       "BD_Supplier",                                          │
 * │    "FieldKeys":    "FSupplierID,FName,FNumber,FStatus,FModifyDate",        │
 * │    "FilterString": "FModifyDate>'{cursor}'",                               │
 * │    "OrderString":  "FModifyDate ASC",                                      │
 * │    "StartRow":     0,                                                      │
 * │    "Limit":        100                                                     │
 * │  }                                                                         │
 * │  Response: [[id,name,code,status,modDate], ...]   ← 数组的数组             │
 * └─────────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>分页游标说明</h2>
 * <p>
 * 金蝶 ExecuteBillQuery 不支持 keyset 分页，采用 {@code StartRow} 偏移量 + {@code Limit}。
 * 本适配器将游标编码为 {@code modifyDate|startRow} 字符串：
 * <ul>
 * <li>首次拉取：cursor = null → FilterString 无时间过滤，StartRow = 0</li>
 * <li>后续拉取：解析上次返回的 nextCursor，提取 modifyDate 和 startRow</li>
 * </ul>
 *
 * <h2>Sandbox 模式</h2>
 * <p>
 * 将 {@code supplier.erp.kingdee.sandbox=true} 时，适配器跳过真实 HTTP，
 * 返回符合金蝶格式的虚构数据，可以驱动完整的 Kafka→Spark ETL 链路测试。
 */
@Slf4j
public class KingdeeErpAdapter implements SupplierPullClient {

  /** 金蝶响应中 LoginResultType 为 1 表示成功。 */
  private static final int LOGIN_SUCCESS = 1;

  private static final DateTimeFormatter KD_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private static final String AUTH_PATH = "/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.Auth.common.kdsvc";
  private static final String QUERY_PATH = "/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc";

  private final KingdeeErpProperties props;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  /** 会话令牌，在首次调用时懒初始化，过期后重新登录。 */
  private volatile String sessionId;

  public KingdeeErpAdapter(KingdeeErpProperties props, ObjectMapper objectMapper) {
    this.props = props;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(props.connectTimeoutMs()))
        .build();
  }

  // ─── SupplierPullClient 实现 ──────────────────────────────────────────────────

  @Override
  public PullResult pull(PullCommand command) {
    log.debug("[Kingdee] pull start supplierId={} cursor={}", command.supplierId(), command.lastCursor());
    if (props.sandbox()) {
      return sandboxPull(command);
    }
    try {
      ensureSession(command.supplierId());
      return realPull(command);
    } catch (SupplierFetchException ex) {
      throw ex; // 透传已分类的异常
    } catch (IOException ex) {
      throw new SupplierFetchException.TimeoutException(
          command.supplierId(),
          "Kingdee HTTP IO error: " + ex.getMessage(),
          ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new SupplierFetchException.TimeoutException(
          command.supplierId(), "Kingdee pull interrupted", ex);
    }
  }

  // ─── 真实 HTTP 调用 ────────────────────────────────────────────────────────────

  /**
   * 确保会话令牌有效，首次或会话失效时自动重新登录。
   *
   * <p>
   * 金蝶的会话令牌通过 Cookie 或显式传参两种方式使用，
   * 这里选择在后续 Query 请求头中附加 SessionId。
   */
  private void ensureSession(long supplierId) throws IOException, InterruptedException {
    if (sessionId != null)
      return;
    sessionId = doLogin(supplierId);
  }

  private String doLogin(long supplierId) throws IOException, InterruptedException {
    var body = objectMapper.writeValueAsString(Map.of(
        "acctID", props.acctId(),
        "username", props.username(),
        "password", props.password(), // 金蝶要求调用方预先 MD5 小写处理
        "lcid", 2052 // 2052 = 简体中文
    ));

    var request = HttpRequest.newBuilder()
        .uri(URI.create(props.baseUrl() + AUTH_PATH))
        .timeout(Duration.ofMillis(props.readTimeoutMs()))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    checkHttpStatus(supplierId, resp.statusCode(), resp.body());

    JsonNode json = parseJson(supplierId, resp.body());
    int loginResult = json.path("LoginResultType").asInt(-1);
    if (loginResult != LOGIN_SUCCESS) {
      throw new SupplierFetchException.AuthException(supplierId, resp.statusCode());
    }
    return json.path("KDSVCSessionId").asText();
  }

  private PullResult realPull(PullCommand command)
      throws IOException, InterruptedException {

    // ── 解析游标 ──────────────────────────────────────────────────────────────
    CursorParts cursor = CursorParts.parse(command.lastCursor());
    String filter = cursor.modifyDate == null
        ? ""
        : "FModifyDate>'" + cursor.modifyDate + "'";

    var queryBody = objectMapper.writeValueAsString(Map.of(
        "FormId", "BD_Supplier",
        "FieldKeys", "FSupplierID,FName,FNumber,FStatus,FModifyDate",
        "FilterString", filter,
        "OrderString", "FModifyDate ASC",
        "StartRow", cursor.startRow,
        "Limit", props.pageSize()));

    var request = HttpRequest.newBuilder()
        .uri(URI.create(props.baseUrl() + QUERY_PATH))
        .timeout(Duration.ofMillis(props.readTimeoutMs()))
        .header("Content-Type", "application/json")
        .header("Cookie", "KDSVCSessionId=" + sessionId)
        .POST(HttpRequest.BodyPublishers.ofString(queryBody))
        .build();

    HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    // 401/403 说明 session 过期，清除后让调用方通过 retryCount 触发重试
    if (resp.statusCode() == 401 || resp.statusCode() == 403) {
      sessionId = null;
      throw new SupplierFetchException.AuthException(command.supplierId(), resp.statusCode());
    }
    if (resp.statusCode() == 429) {
      throw new SupplierFetchException.RateLimitedException(command.supplierId(), null);
    }
    String rawPayload = resp.body();
    checkHttpStatus(command.supplierId(), resp.statusCode(), resp.body());

    // ── 解析响应 ──────────────────────────────────────────────────────────────
    // 金蝶 ExecuteBillQuery 返回格式：[ [id, name, code, status, modDate], ... ]
    JsonNode root = parseJson(command.supplierId(), resp.body());
    if (!root.isArray()) {
      throw new SupplierFetchException.DataException(
          command.supplierId(), snippet(resp.body()), null);
    }
    ArrayNode rows = (ArrayNode) root;
    int count = rows.size();

    // ── 计算下一个游标 ────────────────────────────────────────────────────────
    String nextCursor;
    if (count < props.pageSize()) {
      // 最后一页：推进 modifyDate 到本批最新记录时间，startRow 归零
      String lastModify = count > 0
          ? rows.get(count - 1).get(4).asText() // FModifyDate 在第 5 列
          : cursor.modifyDate;
      nextCursor = new CursorParts(lastModify, 0).encode();
    } else {
      // 还有下一页：相同 filterString，startRow 前进
      nextCursor = new CursorParts(cursor.modifyDate, cursor.startRow + count).encode();
    }

    log.info("[Kingdee] pull done supplierId={} count={} nextCursor={}",
        command.supplierId(), count, nextCursor);

    return new PullResult(nextCursor, count, LocalDateTime.now(), rawPayload);
  }

  // ─── Sandbox 模式（内置 mock，驱动 ETL 测试）────────────────────────────────────

  /**
   * Sandbox 返回符合金蝶真实格式的虚构供应商数据（数组的数组）。
   *
   * <p>
   * 下游 Spark ETL 按 {@code supplierCode} 前缀 {@code KD_} 选择金蝶解析器，
   * 因此 sandbox 数据与真实数据在 JSON 结构上完全一致。
   */
  private PullResult sandboxPull(PullCommand command) {
    CursorParts cursor = CursorParts.parse(command.lastCursor());
    int page = cursor.startRow / props.pageSize(); // 演示用逻辑分页

    // 每次最多返回 2 页，之后 nextCursor 不变（幂等测试）
    if (page >= 2) {
      log.debug("[Kingdee-Sandbox] supplierId={} no more pages", command.supplierId());
      return new PullResult(command.lastCursor(), 0, LocalDateTime.now(), "[]");
    }

    String modDate = LocalDateTime.now().format(KD_DATE_FMT);
    List<List<Object>> rows = List.of(
        List.of(command.supplierId() * 1000L + 1, "北京金蝶物资有限公司", "KD_SUP_001", "A", modDate),
        List.of(command.supplierId() * 1000L + 2, "上海金蝶电子器件厂", "KD_SUP_002", "A", modDate),
        List.of(command.supplierId() * 1000L + 3, "广州金蝶原材料供应商", "KD_SUP_003", "B", modDate));

    String nextCursor = new CursorParts(modDate, cursor.startRow + rows.size()).encode();
    log.debug("[Kingdee-Sandbox] supplierId={} returning {} rows nextCursor={}",
        command.supplierId(), rows.size(), nextCursor);

    return new PullResult(nextCursor, rows.size(), LocalDateTime.now(), writePayload(rows, command.supplierId()));
  }

  // ─── 工具方法 ─────────────────────────────────────────────────────────────────

  private void checkHttpStatus(long supplierId, int status, String body) {
    if (status == 200)
      return;
    if (status == 401 || status == 403) {
      throw new SupplierFetchException.AuthException(supplierId, status);
    }
    if (status == 429) {
      throw new SupplierFetchException.RateLimitedException(supplierId, null);
    }
    if (status >= 500) {
      throw new SupplierFetchException.UnavailableException(supplierId, status,
          "Kingdee server error " + status);
    }
    throw new SupplierFetchException.DataException(supplierId, snippet(body), null);
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
   * 金蝶游标格式：{@code yyyy-MM-dd HH:mm:ss|startRow}
   * 两段用竖线分隔，startRow 为整型偏移量。
   */
  private record CursorParts(String modifyDate, int startRow) {

    static CursorParts parse(String cursor) {
      if (cursor == null || cursor.isBlank())
        return new CursorParts(null, 0);
      int sep = cursor.lastIndexOf('|');
      if (sep < 0)
        return new CursorParts(cursor, 0);
      String date = cursor.substring(0, sep);
      int row;
      try {
        row = Integer.parseInt(cursor.substring(sep + 1));
      } catch (NumberFormatException ex) {
        row = 0;
      }
      return new CursorParts(date, row);
    }

    String encode() {
      return (modifyDate == null ? "" : modifyDate) + "|" + startRow;
    }
  }
}
