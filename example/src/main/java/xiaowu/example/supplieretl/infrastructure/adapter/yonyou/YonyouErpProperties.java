package xiaowu.example.supplieretl.infrastructure.adapter.yonyou;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用友 BIP（YonBIP）OpenAPI 连接配置。
 *
 * application.yml 示例：
 *
 * <pre>
 * supplier:
 *   erp:
 *     yonyou:
 *       base-url: https://open.yonyoucloud.com
 *       app-id: your_app_id
 *       app-secret: your_app_secret
 *       corp-code: your_corp_code    # 租户编码
 *       connect-timeout-ms: 5000
 *       read-timeout-ms: 15000
 *       page-size: 100
 *       sandbox: true
 * </pre>
 *
 * <p>
 * <b>用友 BIP 鉴权方式</b>：OAuth2 Client Credentials，
 * AppID + AppSecret → Access Token（有效期 7200s）。
 * Token 到期后自动刷新，无需重启服务。
 */
@ConfigurationProperties(prefix = "supplier.erp.yonyou")
public record YonyouErpProperties(
    String baseUrl,
    String appId,
    String appSecret,
    String corpCode,
    int connectTimeoutMs,
    int readTimeoutMs,
    int pageSize,
    boolean sandbox) {

  public YonyouErpProperties {
    connectTimeoutMs = connectTimeoutMs <= 0 ? 5_000 : connectTimeoutMs;
    readTimeoutMs = readTimeoutMs <= 0 ? 15_000 : readTimeoutMs;
    pageSize = (pageSize <= 0 || pageSize > 500) ? 100 : pageSize;
  }
}
