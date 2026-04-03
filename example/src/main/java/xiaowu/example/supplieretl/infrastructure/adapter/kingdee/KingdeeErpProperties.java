package xiaowu.example.supplieretl.infrastructure.adapter.kingdee;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 金蝶云星空（Kingdee Cloud）连接配置。
 *
 * <p>
 * 在 application.yml 中对应：
 *
 * <pre>
 * supplier:
 *   erp:
 *     kingdee:
 *       base-url: https://your-kingdee-host/K3Cloud
 *       acct-id: 123456789
 *       username: api_user
 *       password: your_md5_password   # 金蝶要求传 MD5 小写摘要
 *       connect-timeout-ms: 5000
 *       read-timeout-ms: 15000
 *       page-size: 100
 *       sandbox: true                 # true = 使用内置 mock 数据，不发真实 HTTP
 * </pre>
 *
 * <p>
 * <b>关于 sandbox 模式</b>：
 * 大多数企业没有金蝶测试环境，将 sandbox 设为 true 时适配器返回符合金蝶真实格式的
 * mock 数据，让整条 Kafka→Spark ETL 链路可以完整跑通，无需真实 ERP。
 * 生产上线时将 sandbox 置为 false，填入真实凭证即可切换。
 */
@ConfigurationProperties(prefix = "supplier.erp.kingdee")
public record KingdeeErpProperties(
    String baseUrl,
    String acctId,
    String username,
    String password,
    int connectTimeoutMs,
    int readTimeoutMs,
    int pageSize,
    boolean sandbox) {

  public KingdeeErpProperties {
    connectTimeoutMs = connectTimeoutMs <= 0 ? 5_000 : connectTimeoutMs;
    readTimeoutMs = readTimeoutMs <= 0 ? 15_000 : readTimeoutMs;
    pageSize = (pageSize <= 0 || pageSize > 500) ? 100 : pageSize;
  }
}
