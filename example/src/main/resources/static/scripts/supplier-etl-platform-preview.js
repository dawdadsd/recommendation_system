/* =================================================================
   Rule-Driven ETL Platform — Scene Animation Controller
   ================================================================= */

const scenes = [
  {
    key: "erp",
    title: "ERP 供应商数据接入",
    text: "金蝶与用友的供应商主数据先进入统一事件封套，系统按规则版本自动标准化状态、时间与税号口径，随后落入 DWD 供应商分层表。",
    activeTicket: "erp",
    sourceLabel: "ERP Master Data",
    rawTopic: "etl.raw.masterdata",
    ruleLabel: "supplier-status-v3",
    engineLabel: "Normalize + Validate",
    targetLabel: "DWD Supplier",
    ruleEditorTitle: "供应商状态映射规则",
    ruleLines: [
      "A  →  ACTIVE",
      "B  →  INACTIVE",
      "Y  →  ACTIVE",
      "N  →  INACTIVE",
    ],
    throughput: "18,420 / min",
    dirty: "0.7%",
    version: "v2026.04.09-erp",
    successRate: "99.3%",
    dlq: "12",
    replay: "4",
    activeDataset: "erp",
    infraHighlights: ["kafka", "mysql"],
    infraDetails: {
      kafka: "3 brokers · topic: etl.raw.masterdata",
      mysql: "DWD supplier_master · 42k rows",
      redis: "Cache: supplier_status_map",
      mongodb: "Archive: supplier_history",
      elasticsearch: "Index: supplier_search",
    },
    audit: {
      time: now(),
      title: "ERP supplier normalized",
      detail: "status: A → ACTIVE · KD_SUP_001",
    },
  },
  {
    key: "payment",
    title: "支付流水清洗与金额标准化",
    text: "支付宝与微信的交易回调进入 Kafka raw topic，系统自动做金额单位转换（元→分）、状态码映射、交易去重，最终写入 FACT 支付事实表。",
    activeTicket: "payment",
    sourceLabel: "Payment Streams",
    rawTopic: "etl.raw.payment",
    ruleLabel: "payment-amount-v2",
    engineLabel: "Convert + Dedup",
    targetLabel: "FACT Payment",
    ruleEditorTitle: "支付金额与状态映射",
    ruleLines: [
      "amount_yuan × 100 → amount_fen",
      "TRADE_SUCCESS → SUCCESS",
      "WAIT_BUYER_PAY → PENDING",
      "TRADE_CLOSED → CANCELLED",
    ],
    throughput: "32,180 / min",
    dirty: "1.2%",
    version: "v2026.04.09-pay",
    successRate: "98.8%",
    dlq: "27",
    replay: "8",
    activeDataset: "payment",
    infraHighlights: ["kafka", "mysql", "redis"],
    infraDetails: {
      kafka: "topic: etl.raw.payment · 32k/min",
      mysql: "FACT payment_events · 1.2M rows",
      redis: "Dedup: trade_no_set · 98k keys",
      mongodb: "Log: payment_raw_archive",
      elasticsearch: "Index: payment_search",
    },
    audit: {
      time: now(),
      title: "Payment trade settled",
      detail: "¥199.00 → 19900 fen · ALI202604090001",
    },
  },
  {
    key: "hr",
    title: "人员组织数据同步与维度构建",
    text: "HR 系统的入离职、调岗事件流自动解析成标准化的员工快照，经过组织树校验后写入 DIM 人员维度表，同时驱动权限和审批节点更新。",
    activeTicket: "hr",
    sourceLabel: "HR / OA Events",
    rawTopic: "etl.raw.employee",
    ruleLabel: "hr-org-mapping-v1",
    engineLabel: "OrgTree Validate",
    targetLabel: "DIM Employee",
    ruleEditorTitle: "人员状态与组织映射",
    ruleLines: [
      "IN_SERVICE → ACTIVE",
      "RESIGNED  → INACTIVE",
      "orgCode → dept_hierarchy",
      "positionLevel → grade_band",
    ],
    throughput: "4,620 / min",
    dirty: "0.3%",
    version: "v2026.04.09-hr",
    successRate: "99.7%",
    dlq: "3",
    replay: "1",
    activeDataset: "hr",
    infraHighlights: ["kafka", "mysql", "mongodb"],
    infraDetails: {
      kafka: "topic: etl.raw.employee · 4.6k/min",
      mysql: "DIM employee_snapshot · 15k rows",
      redis: "Cache: org_tree_map",
      mongodb: "Store: employee_changelog",
      elasticsearch: "Index: employee_directory",
    },
    audit: {
      time: now(),
      title: "HR org validated",
      detail: "employee E10384 → OPS-CN · DIM routed",
    },
  },
  {
    key: "documents",
    title: "非结构化文档汇聚与大模型知识库",
    text: "不论是 Excel、Word 还是 PDF 文档，皆可抽取解析为结构化切片与向量流，全程追溯与查询，为企业 AI 应用（RAG）搭建高可信知识库底座。",
    activeTicket: "documents",
    sourceLabel: "Unstructured Files",
    rawTopic: "etl.raw.documents",
    ruleLabel: "doc-parse-vectorize",
    engineLabel: "OCR + Chunk + Embed",
    targetLabel: "Vector DB (KB)",
    ruleEditorTitle: "文档切块溯源策略",
    ruleLines: [
      "Format    →  PDF/Word/Excel",
      "ChunkSize →  512 tokens",
      "Overlap   →  50 tokens",
      "Trace     →  Source_Line_Exact",
    ],
    audit: {
      time: "17:12:08",
      title: "Document Vectorized",
      desc: "Report chunked. Indexing mapped to doc: Q2_Report.pdf",
    },
    activeDataset: "documents",
    infraHighlights: ["kafka", "elasticsearch", "mongodb"],
    infraDetails: {
      kafka: "Topic `etl.raw.documents` bytes flowing at 12MB/s.",
      elasticsearch: "Vector embeddings inserted [100% matched].",
      mongodb: "Base document archive saved in GridFS.",
    },
  },
  {
    key: "governance",
    title: "数据治理、回放与质量审计",
    text: "平台自动将处理失败的消息隔离到 DLQ，运维可按规则版本回放、逐条修复，审计日志记录每一次规则变更和数据血缘，闭环整个数据平台。",
    activeTicket: null,
    sourceLabel: "All Sources",
    rawTopic: "etl.dlq.*",
    ruleLabel: "governance-audit-v1",
    engineLabel: "Replay + Repair",
    targetLabel: "Audit Log",
    ruleEditorTitle: "治理回放规则",
    ruleLines: [
      "DLQ.isolate → quarantine",
      "replay(ruleVersion) → reprocess",
      "audit.log(changeSet)",
      "lineage.trace(sourceId)",
    ],
    throughput: "55,220 / min",
    dirty: "0.8%",
    version: "v2026.04.09-gov",
    successRate: "99.2%",
    dlq: "42",
    replay: "13",
    activeDataset: null,
    infraHighlights: ["kafka", "mysql", "redis", "mongodb", "elasticsearch"],
    infraDetails: {
      kafka: "DLQ: etl.dlq.* · 42 pending",
      mysql: "audit_log · 128 rule versions",
      redis: "Lock: replay_semaphore",
      mongodb: "lineage_trace · 3 shards",
      elasticsearch: "audit_index · full-text query",
    },
    audit: {
      time: now(),
      title: "DLQ replay triggered",
      detail: "rule v3 → v4 · 42 records reprocessed",
    },
  },
];

const SCENE_DURATION_MS = 5000;

const state = {
  activeIndex: 0,
  autoplay: true,
  timerId: null,
};

const infraState = {
  activeTarget: "kafka",
};

const INFRA_TEST_FORMS = {
  kafka: {
    type: "KAFKA",
    defaults: {
      bootstrapServers: "localhost:9092",
      topic: "supplier.raw.data",
      clientId: "supplier-etl-ui",
    },
    fields: [
      {
        name: "bootstrapServers",
        label: "Bootstrap Servers",
        placeholder: "localhost:9092",
        help: "多个 broker 使用逗号分隔，可填本地或云端地址",
        full: true,
      },
      {
        name: "topic",
        label: "Topic",
        placeholder: "supplier.raw.data",
      },
      {
        name: "clientId",
        label: "Client ID",
        placeholder: "supplier-etl-ui",
      },
    ],
  },
  mysql: {
    type: "MYSQL",
    defaults: {
      jdbcUrl: "jdbc:mysql://localhost:3306/test",
      username: "root",
      password: "",
      validationQuery: "SELECT 1",
    },
    fields: [
      {
        name: "jdbcUrl",
        label: "JDBC URL",
        placeholder: "jdbc:mysql://your-host:3306/db",
        help: "支持本地 MySQL 或云数据库地址",
        full: true,
      },
      {
        name: "username",
        label: "Username",
        placeholder: "root",
      },
      {
        name: "password",
        label: "Password",
        placeholder: "******",
        inputType: "password",
      },
      {
        name: "validationQuery",
        label: "Validation Query",
        placeholder: "SELECT 1",
        full: true,
      },
    ],
  },
  redis: {
    type: "REDIS",
    defaults: {
      host: "localhost",
      port: "6379",
      database: "0",
      password: "",
      keyPattern: "*",
    },
    fields: [
      {
        name: "host",
        label: "Host",
        placeholder: "localhost / redis.example.com",
      },
      {
        name: "port",
        label: "Port",
        placeholder: "6379",
        inputType: "number",
      },
      {
        name: "database",
        label: "Database",
        placeholder: "0",
        inputType: "number",
      },
      {
        name: "password",
        label: "Password",
        placeholder: "******",
        inputType: "password",
      },
      {
        name: "keyPattern",
        label: "Key Pattern",
        placeholder: "*",
        full: true,
      },
    ],
  },
  excel: {
    type: "EXCEL",
    defaults: {
      sheetName: "",
      headerRowIndex: "0",
      sampleSize: "20",
    },
    fields: [
      {
        name: "file",
        label: "Excel File",
        inputType: "file",
        accept: ".xlsx",
        help: "仅支持 .xlsx，作为解析测试，不写入系统",
        full: true,
      },
      {
        name: "sheetName",
        label: "Sheet Name",
        placeholder: "可留空，默认首个 sheet",
      },
      {
        name: "headerRowIndex",
        label: "Header Row Index",
        placeholder: "0",
        inputType: "number",
      },
      {
        name: "sampleSize",
        label: "Sample Size",
        placeholder: "20",
        inputType: "number",
      },
    ],
  },
};

function now() {
  const d = new Date();
  return (
    d.getHours().toString().padStart(2, "0") +
    ":" +
    d.getMinutes().toString().padStart(2, "0") +
    ":" +
    d.getSeconds().toString().padStart(2, "0")
  );
}

function $(sel) {
  return document.querySelector(sel);
}
function $all(sel) {
  return Array.from(document.querySelectorAll(sel));
}

/* ---- Progress bar ---- */
function restartProgress() {
  const bar = $("[data-scene-progress]");
  if (!bar) return;
  bar.classList.remove("is-animating");
  void bar.offsetWidth;
  if (state.autoplay) bar.classList.add("is-animating");
}

/* ---- Pipeline stepping animation ---- */
function animatePipeline() {
  const nodes = $all(".pipeline-node");
  nodes.forEach((n) => {
    n.classList.remove("is-active", "is-stepping");
  });

  nodes.forEach((node, i) => {
    setTimeout(() => {
      node.classList.add("is-active", "is-stepping");
      // Remove stepping glow after a bit, keep active
      setTimeout(() => node.classList.remove("is-stepping"), 800);
    }, i * 400);
  });
}

/* ---- Add audit row ---- */
function pushAuditRow(audit) {
  const feed = $("#audit-feed");
  if (!feed) return;

  const row = document.createElement("article");
  row.className = "audit-row";
  row.innerHTML =
    "<span>" +
    audit.time +
    "</span>" +
    "<strong>" +
    audit.title +
    "</strong>" +
    "<p>" +
    audit.detail +
    "</p>";

  feed.prepend(row);

  // Keep max 5 rows
  while (feed.children.length > 5) {
    feed.removeChild(feed.lastChild);
  }
}

/* ---- Apply Scene ---- */
function applyScene(index) {
  const scene = scenes[index];
  if (!scene) return;
  state.activeIndex = index;

  // Sidebar active
  $all(".sidebar-item").forEach((btn) => btn.classList.remove("is-current"));
  const activeBtn = $('[data-scene-button="' + scene.key + '"]');
  if (activeBtn) activeBtn.classList.add("is-current");

  // Scene title + text
  const titleEl = $("#scene-title");
  const textEl = $("#scene-text");
  if (titleEl) titleEl.textContent = scene.title;
  if (textEl) textEl.textContent = scene.text;

  // Source tickets
  $all(".source-ticket").forEach((t) => t.classList.remove("is-live"));
  if (scene.activeTicket) {
    const ticket = $('[data-ticket="' + scene.activeTicket + '"]');
    if (ticket) ticket.classList.add("is-live");
  }

  // Source label
  const srcLabel = $("#source-label");
  if (srcLabel) srcLabel.textContent = scene.sourceLabel;

  // Pipeline labels
  const rawLabel = $("#raw-topic-label");
  const ruleLabel = $("#rule-label");
  const engineLabel = $("#engine-label");
  const targetLabel = $("#target-label");
  if (rawLabel) rawLabel.textContent = scene.rawTopic;
  if (ruleLabel) ruleLabel.textContent = scene.ruleLabel;
  if (engineLabel) engineLabel.textContent = scene.engineLabel;
  if (targetLabel) targetLabel.textContent = scene.targetLabel;

  // Rule editor
  const ruleTitle = $("#rule-editor-title");
  const ruleLines = $("#rule-lines");
  if (ruleTitle) ruleTitle.textContent = scene.ruleEditorTitle;
  if (ruleLines) {
    ruleLines.innerHTML = scene.ruleLines
      .map((l) => "<span>" + l + "</span>")
      .join("");
  }

  // Monitor cards
  const mThroughput = $("#monitor-throughput");
  const mDirty = $("#monitor-dirty");
  const mVersion = $("#monitor-version");
  if (mThroughput) mThroughput.textContent = scene.throughput;
  if (mDirty) mDirty.textContent = scene.dirty;
  if (mVersion) mVersion.textContent = scene.version;

  // Sidebar metrics
  const mSuccess = $("#metric-success-rate");
  const mDlq = $("#metric-dlq");
  const mReplay = $("#metric-replay");
  if (mSuccess) mSuccess.textContent = scene.successRate;
  if (mDlq) mDlq.textContent = scene.dlq;
  if (mReplay) mReplay.textContent = scene.replay;

  // Dataset cards
  $all(".dataset-card").forEach((c) => c.classList.remove("active"));
  if (scene.activeDataset) {
    const card = $('[data-table="' + scene.activeDataset + '"]');
    if (card) card.classList.add("active");
  }

  // Infrastructure nodes
  $all(".infra-node").forEach((n) => n.classList.remove("is-active"));
  if (scene.infraHighlights) {
    scene.infraHighlights.forEach((infraKey, i) => {
      setTimeout(() => {
        const node = $('[data-infra="' + infraKey + '"]');
        if (node) node.classList.add("is-active");
      }, i * 200);
    });
  }
  // Infrastructure details
  if (scene.infraDetails) {
    var detailMap = {
      kafka: "#infra-kafka-detail",
      mysql: "#infra-mysql-detail",
      redis: "#infra-redis-detail",
      mongodb: "#infra-mongo-detail",
      elasticsearch: "#infra-es-detail",
    };
    Object.keys(scene.infraDetails).forEach(function (key) {
      var el = $(detailMap[key]);
      if (el) el.textContent = scene.infraDetails[key];
    });
  }

  // Push audit
  pushAuditRow(scene.audit);

  // Pipeline step animation
  animatePipeline();

  // Progress bar
  restartProgress();

  // Hero orbit notes sync
  $all("[data-hero-note]").forEach((note) => note.classList.remove("is-active"));
  const noteTarget =
    scene.key === "payment" || scene.key === "hr"
      ? "erp"
      : scene.key === "documents"
        ? "documents"
        : "governance";
  const activeNote = $(`[data-hero-note="${noteTarget}"]`);
  if (activeNote) {
    activeNote.classList.add("is-active");
  }
}

/* ---- Auto-play scheduling ---- */
function scheduleNext() {
  clearTimeout(state.timerId);
  if (!state.autoplay) return;

  state.timerId = setTimeout(() => {
    const next = (state.activeIndex + 1) % scenes.length;
    applyScene(next);
    scheduleNext();
  }, SCENE_DURATION_MS);
}

function setAutoplay(enabled) {
  state.autoplay = enabled;
  const btn = $("[data-autoplay-toggle]");
  if (btn) {
    btn.classList.toggle("is-paused", !enabled);
    btn.textContent = enabled ? "自动讲解中" : "已暂停";
    btn.setAttribute("aria-pressed", String(enabled));
  }
  restartProgress();
  scheduleNext();
}

/* ---- Bind events ---- */
function bindSidebarButtons() {
  $all("[data-scene-button]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const key = btn.dataset.sceneButton;
      jumpToScene(key);
    });
  });
}

function jumpToScene(key) {
  const idx = scenes.findIndex((s) => s.key === key);
  if (idx < 0) return;
  // Make sure we are in the pipeline view
  $("[data-nav='pipeline']").click();
  applyScene(idx);
  scheduleNext();
}

function bindAutoplayToggle() {
  const btn = $("[data-autoplay-toggle]");
  if (!btn) return;
  btn.addEventListener("click", () => setAutoplay(!state.autoplay));
}

function bindViewNavigation() {
  // Navigation blocks in the left sidebar
  const navBtns = $all("[data-nav]");
  navBtns.forEach((btn) => {
    btn.addEventListener("click", () => {
      const targetView = btn.dataset.nav; // 'pipeline', 'infra', or 'architecture'

      // Update sidebar active states
      $all(".sidebar-item").forEach((b) => b.classList.remove("is-current"));
      btn.classList.add("is-current");

      // Update views
      $all(".console-view").forEach((v) => v.classList.remove("active"));
      if (targetView === "infra") {
        $("#view-infra").classList.add("active");
        setAutoplay(false); // Pause animation when entering config view
      } else if (targetView === "architecture") {
        $("#view-architecture").classList.add("active");
        setAutoplay(false); // Pause animation for architecture flow
      } else {
        $("#view-pipeline").classList.add("active");
        // Resume from where it was
        setAutoplay(true);
      }
    });
  });

  // Infra details selection (Left side list -> Right side details)
  const infraItems = $all(".infra-list-item");
  infraItems.forEach((item) => {
    item.addEventListener("click", () => {
      // Toggle active list item
      infraItems.forEach((i) => i.classList.remove("active"));
      item.classList.add("active");

      // Update details panel based on selection
      const target = item.dataset.infraTarget;
      updateInfraDetailPanel(target);
    });
  });

  // Global Architecture: "Play Full Flow" button -> jumps to Pipeline view (Scene 0)
  $("#btn-play-full-flow").addEventListener("click", () => {
    jumpToScene("erp");
    setAutoplay(true);
  });

  // Global Architecture: Click Data Source arch-boxes to open Drawer
  const drawerOverlay = $("#source-detail-drawer");
  const btnCloseDrawer = $("#btn-close-drawer");
  const btnJumpPipeline = $("#btn-jump-to-pipeline");

  // Keep track of which scene relates to the opened drawer
  let currentDrawerSceneKey = "erp";

  const sourceDataModalMap = {
    erp: {
      badge: "ERP",
      title: "金蝶/用友主数据接入",
      doc: "处理来自企业后端如金蝶、用友或 SAP 的供应商主数据（Master Data）。系统通过 CDC (Canal/Debezium) 实时监听 Binlog 并将其投递至 Kafka。数据随后由实时引擎 (Flink) 消费，过滤无意义字段并将其异构的状态码统一映射为全局标准枚举（如 ACTIVE/INACTIVE）。最后以幂等更新的形式写入 DWD (Data Warehouse Data) 层。",
      code: '{\n  "payload": {\n    "supp_id": "K3-992A",\n    "biz_name": "XXX Technology Co.",\n    "status_code": "1", // To be normalized\n    "creation_time": 1698218171000\n  },\n  "meta": {\n    "source": "kingdee.K3_CLOUD",\n    "type": "INSERT"\n  }\n}',
      sceneKey: "erp",
    },
    fin: {
      badge: "FIN",
      title: "网关支付与对账流水",
      doc: "处理支付宝、微信支付回调以及银联交易流水数据。支付数据具备极严的并发、防重和精度要求。引擎会对高频乱序回调进行滚动窗口去重拦截，同时标准化金额单位（如统一转换为'分'，解决精度计算丢失问题），在支付状态为 SUCCESS 时更新底层单据流水库。",
      code: '{\n  "event_id": "EVT-pay-811c-x",\n  "trade_no": "ALI202604090001",\n  "amount_yuan": "199.00", // Converts to 19900 fen\n  "pay_status": "SUCCESS",\n  "gateway_ts": "2026-04-09T16:48:00Z"\n}',
      sceneKey: "payment",
    },
    hr: {
      badge: "HR / OA",
      title: "人员组织架构同步",
      doc: "接入飞书、钉钉、企微等外部办公平台的人员异动回调事件（离职、调岗、新增等）。面对 OA 平台复杂的嵌套层级结构与树形数组，平台会对 JSON 树执行'摊平策略'(Flatten)，降解数组为扁平属性，并通过关联系统字典（sys_dict:org）确保内部工号体系合规。",
      code: '[\n  {\n    "emp_id": "E10384",\n    "status": "ACTIVE",\n    "department": {\n       "dept_id": "D9x8",\n       "name": "Ops"\n    },\n    "mgr_id": "E9923"\n  }\n]',
      sceneKey: "hr",
    },
    docs: {
      badge: "DOCS",
      title: "非结构化办公文档 (Excel/PDF)",
      doc: "处理存储在 OSS/S3 的非结构化文档（包括报表、归档 PDF、Excel 清单等）。平台首先抓取并使用 OCR 和文本提取(pdf2text)。随后借助大语言模型 (LLM) 获取 512 Tokens 为一段的知识切片。生成 Dense Vectors 后写入 Elasticsearch 向量网格中，为下游 RAG （智能推荐或企业知识库）提供 100% 粒度溯源能力。",
      code: '<< Binary Data Stream Loaded from S3 >>\n...\n[EXTRACTED CHUNK 04]:\n"Regarding the 2026 Q2 Supplier Audits,\nsupplier KD_SUP_001 successfully passed..."\n\n[EMBEDDING GEN]:\n[-0.0432, 0.1291, ..., 0.0031] (Dim: 1536)',
      sceneKey: "documents",
    },
  };

  $all(".arch-box[data-source-detail]").forEach((box) => {
    box.addEventListener("click", () => {
      const type = box.dataset.sourceDetail;
      const data = sourceDataModalMap[type];
      if (data) {
        $("#drawer-badge").textContent = data.badge;
        $("#drawer-title").textContent = data.title;
        $("#drawer-doc").textContent = data.doc;
        $("#drawer-code").textContent = data.code;
        currentDrawerSceneKey = data.sceneKey;

        drawerOverlay.classList.remove("hidden");
      }
    });
  });

  // Close drawer
  btnCloseDrawer.addEventListener("click", () => {
    drawerOverlay.classList.add("hidden");
  });
  drawerOverlay.addEventListener("click", (e) => {
    if (e.target === drawerOverlay) {
      drawerOverlay.classList.add("hidden");
    }
  });

  // Jump from Drawer to corresponding Pipeline Scene
  btnJumpPipeline.addEventListener("click", () => {
    drawerOverlay.classList.add("hidden");
    jumpToScene(currentDrawerSceneKey);
    setAutoplay(true);
  });
}

function updateInfraDetailPanel(target) {
  const config = {
    kafka: {
      title: "Kafka 数据总线",
      desc: "测试 Kafka broker 与 topic 的连通性，既可以填写本地 Kafka，也可以填写云上 Kafka。",
      addr: "localhost:9092 / cloud-kafka:9092",
      ver: "Apache Kafka",
      topics: "broker + topic existence",
      health: "测试项：describeCluster + topic 校验",
      logs: [
        "[LOCAL] <span class='log-level info'>INFO</span> localhost:9092",
        "[CLOUD] <span class='log-level info'>INFO</span> broker-1.kafka.aliyun.com:9092",
        "[CHECK] <span class='log-level warn'>WARN</span> Topic 不存在会返回失败结果。",
      ],
    },
    mysql: {
      title: "MySQL 业务库",
      desc: "测试外部 MySQL 连接并执行 validation query，支持本地或云端 MySQL。",
      addr: "jdbc:mysql://localhost:3306/test",
      ver: "MySQL 8.x",
      topics: "JDBC + SELECT 1",
      health: "测试项：Driver + Connection + Query",
      logs: [
        "[LOCAL] <span class='log-level info'>INFO</span> jdbc:mysql://localhost:3306/test",
        "[CLOUD] <span class='log-level info'>INFO</span> jdbc:mysql://rm-xxxx.mysql.rds.aliyuncs.com:3306/demo",
        "[CHECK] <span class='log-level warn'>WARN</span> 默认 validation query = SELECT 1",
      ],
    },
    redis: {
      title: "Redis 缓存与规则",
      desc: "测试 Redis 单点连接与 PING，支持本地 Redis 和云上 Redis。",
      addr: "localhost:6379 / redis.example.com:6379",
      ver: "Redis 7.x",
      topics: "PING + db selection",
      health: "测试项：host/port/db/password",
      logs: [
        "[LOCAL] <span class='log-level info'>INFO</span> localhost:6379 / db=0",
        "[CLOUD] <span class='log-level info'>INFO</span> redis-prod.example.com:6379",
        "[CHECK] <span class='log-level warn'>WARN</span> 仅做 PING，不执行 KEYS 扫描。",
      ],
    },
    excel: {
      title: "Excel 文件接入",
      desc: "测试本地上传 Excel 文件是否可解析，返回 sheet、表头和样本行预览。",
      addr: "local file upload (.xlsx)",
      ver: "Apache POI",
      topics: "sheet + header + sample rows",
      health: "测试项：文件格式与解析结果",
      logs: [
        "[FILE] <span class='log-level info'>INFO</span> 仅支持 .xlsx",
        "[CHECK] <span class='log-level info'>INFO</span> 返回 sheetNames / headers / sampleRows",
        "[NOTE] <span class='log-level warn'>WARN</span> 该测试不连接网络，仅验证解析能力。",
      ],
    },
  };

  const data = config[target];
  if (!data) return;
  infraState.activeTarget = target;

  const panel = $("#view-infra .infra-detail-panel");
  panel.querySelector("#infra-panel-title").textContent = data.title;
  panel.querySelector("#infra-panel-desc").textContent = data.desc;
  panel.querySelector("#infra-prop-address").textContent = data.addr;
  panel.querySelector("#infra-prop-version").textContent = data.ver;
  panel.querySelector("#infra-prop-topics").textContent = data.topics;
  panel.querySelector("#infra-prop-health").textContent = data.health;

  const logContainer = panel.querySelector("#infra-terminal-logs");
  logContainer.innerHTML = data.logs
    .map((log) => `<div><span class="log-time"></span> ${log}</div>`)
    .join("");

  renderInfraForm(target);
  renderConnectionTestResult("idle", "等待测试", "填写参数后点击“测试连接”。");
}

function renderInfraForm(target) {
  const definition = INFRA_TEST_FORMS[target];
  const container = $("#infra-form-fields");
  if (!definition || !container) return;

  container.innerHTML = definition.fields
    .map((field) => {
      const value = definition.defaults[field.name] ?? "";
      const inputType = field.inputType || "text";
      const fullClass = field.full ? " full" : "";
      const acceptAttr = field.accept ? ` accept="${field.accept}"` : "";
      const valueAttr =
        inputType === "file"
          ? ""
          : ` value="${escapeHtml(String(value))}"`;

      return `
        <div class="connection-form-field${fullClass}">
          <label for="infra-field-${field.name}">${field.label}</label>
          <input
            id="infra-field-${field.name}"
            name="${field.name}"
            type="${inputType}"
            placeholder="${escapeHtml(field.placeholder || "")}"${acceptAttr}${valueAttr}
          />
          ${field.help ? `<small>${escapeHtml(field.help)}</small>` : ""}
        </div>
      `;
    })
    .join("");
}

function bindInfraTestActions() {
  const fillBtn = $("#infra-fill-local");
  const runBtn = $("#infra-run-test");
  if (fillBtn) {
    fillBtn.addEventListener("click", () => {
      renderInfraForm(infraState.activeTarget);
      renderConnectionTestResult("idle", "已填充本地示例", "你可以直接测试，也可以把地址改成云端配置。");
    });
  }
  if (runBtn) {
    runBtn.addEventListener("click", () => {
      void runInfraConnectionTest();
    });
  }
}

async function runInfraConnectionTest() {
  const target = infraState.activeTarget;
  const definition = INFRA_TEST_FORMS[target];
  if (!definition) return;

  renderConnectionTestResult("loading", "测试中", "后端正在执行真实连接测试，请稍候。");

  try {
    let response;
    if (target === "excel") {
      const fileInput = $("#infra-field-file");
      if (!fileInput || !fileInput.files || !fileInput.files[0]) {
        throw new Error("请选择一个 .xlsx 文件");
      }
      const formData = new FormData();
      formData.append("file", fileInput.files[0]);
      appendIfPresent(formData, "sheetName", valueOfField("sheetName"));
      appendIfPresent(formData, "headerRowIndex", valueOfField("headerRowIndex"));
      appendIfPresent(formData, "sampleSize", valueOfField("sampleSize"));
      response = await fetch("/api/examples/data-sources/excel/test", {
        method: "POST",
        body: formData,
      });
    } else {
      const payload = {
        type: definition.type,
        config: buildJsonConfig(target),
      };
      response = await fetch("/api/examples/data-sources/connections/test", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify(payload),
      });
    }

    const data = await parseJsonResponse(response);
    const status = data.success ? "success" : "error";
    const message = formatTestMessage(data.message, data.testedAt);
    renderConnectionTestResult(status, data.success ? "连接测试通过" : "连接测试失败", message, data.detail);
  } catch (error) {
    renderConnectionTestResult("error", "请求失败", error.message || "连接测试请求失败");
  }
}

function buildJsonConfig(target) {
  const definition = INFRA_TEST_FORMS[target];
  const config = {};
  definition.fields.forEach((field) => {
    if (field.inputType === "file") {
      return;
    }
    const value = valueOfField(field.name);
    if (value === "") {
      return;
    }
    if (field.inputType === "number") {
      config[field.name] = Number(value);
      return;
    }
    config[field.name] = value;
  });
  return config;
}

function valueOfField(name) {
  const element = $("#infra-field-" + name);
  return element ? element.value.trim() : "";
}

function appendIfPresent(formData, name, value) {
  if (value !== "") {
    formData.append(name, value);
  }
}

async function parseJsonResponse(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = data.message || `HTTP ${response.status}`;
    throw new Error(message);
  }
  return data;
}

function renderConnectionTestResult(status, title, message, detail) {
  const element = $("#infra-test-result");
  if (!element) return;

  element.className = `connection-test-result is-${status}`;

  let detailBlock = "";
  if (detail && Object.keys(detail).length > 0) {
    detailBlock = `<pre>${escapeHtml(JSON.stringify(detail, null, 2))}</pre>`;
  }

  element.innerHTML = `
    <strong>${escapeHtml(title)}</strong>
    <p>${escapeHtml(message)}</p>
    ${detailBlock}
  `;
}

function formatTestMessage(message, testedAt) {
  if (!testedAt) {
    return message;
  }
  return `${message} (${testedAt})`;
}

function escapeHtml(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

/* ---- Init ---- */
document.addEventListener("DOMContentLoaded", () => {
  bindSidebarButtons();
  bindAutoplayToggle();
  bindViewNavigation();
  bindInfraTestActions();
  initPretextTypography();
  updateInfraDetailPanel("kafka");
  applyScene(0);
  scheduleNext();
  initEarthNetwork();
});

async function initPretextTypography() {
  const targets = document.querySelectorAll("[data-pretext-source]");
  if (!targets.length) return;

  let pretext = null;
  try {
    const mod = await import("https://esm.sh/pretext@0.3.0");
    pretext = mod.default || mod;
  } catch (error) {
    console.warn("Pretext 加载失败，使用页面内置文本回退。", error);
    return;
  }

  targets.forEach((target) => {
    const sourceId = target.getAttribute("data-pretext-source");
    if (!sourceId) return;

    const source = document.getElementById(sourceId);
    if (!source) return;

    const input = source.textContent.trim();
    if (!input) return;

    target.innerHTML = pretext(input);
  });
}

/* ==========================================================
   EARTH NETWORK ANIMATION (Canvas 3D Particles)
   ========================================================== */
function initEarthNetwork() {
  const canvas = document.getElementById("earth-network");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");

  let width, height;
  function resize() {
    width = canvas.parentElement.clientWidth;
    height = canvas.parentElement.clientHeight;
    // Allow it to span across the whole hero background properly
    const dpr = window.devicePixelRatio || 1;
    canvas.width = width * dpr;
    canvas.height = height * dpr;
    canvas.style.width = width + "px";
    canvas.style.height = height + "px";
    ctx.scale(dpr, dpr);
  }

  window.addEventListener("resize", resize);
  resize();

  const particles = [];
  const numParticles = 300; // slightly denser

  // Golden ratio angle
  const phi = Math.PI * (3 - Math.sqrt(5));

  // Data source labels for traceability visualization
  const sourceLabels = [
    "ERP: Order MS",
    "Payment Gateway",
    "HR: Workday",
    "Kafka: clickstream",
    "MySQL: user_db",
    "Log: Nginx",
    "MongoDB: docs",
    "SFTP: excel_exports",
  ];

  for (let i = 0; i < numParticles; i++) {
    const y = 1 - (i / (numParticles - 1)) * 2;
    const r = Math.sqrt(1 - y * y);
    const theta = phi * i;

    const x0 = Math.cos(theta) * r;
    const z0 = Math.sin(theta) * r;
    const y0 = y;

    // Designate a few random particles as data sources
    const isSource =
      i % Math.floor(numParticles / sourceLabels.length) === 0 &&
      sourceLabels.length > 0;
    const label = isSource ? sourceLabels.pop() : null;

    particles.push({
      x0,
      y0,
      z0,
      x: 0,
      y: 0,
      z: 0,
      scale: 1,
      isSource,
      label,
      pulseRadius: Math.random() * 20, // for animation
    });
  }

  let rotationX = 0;
  let rotationY = 0;

  function render() {
    ctx.clearRect(0, 0, width, height);

    // Scale up the radius and center the sphere to act as a cosmic background mesh
    // rather than clustering behind the right-side UI panel
    const radius = Math.min(width * 0.45, height * 0.6);

    // Spin gradually
    rotationY += 0.002;
    rotationX += 0.0005;

    const cosX = Math.cos(rotationX);
    const sinX = Math.sin(rotationX);
    const cosY = Math.cos(rotationY);
    const sinY = Math.sin(rotationY);

    // 3D Matrix Multiplication
    for (let p of particles) {
      // Rotate Y
      let x1 = p.x0 * cosY - p.z0 * sinY;
      let z1 = p.x0 * sinY + p.z0 * cosY;

      // Rotate X
      let y2 = p.y0 * cosX - z1 * sinX;
      let z2 = p.y0 * sinX + z1 * cosX;

      p.z = z2;

      // Simple perspective projection
      const fov = 4;
      p.scale = fov / (fov + p.z);

      // Center the sphere
      p.x = width * 0.5 + x1 * radius * p.scale;
      p.y = height * 0.5 + y2 * radius * p.scale;

      if (p.isSource) {
        p.pulseRadius += 0.3;
        if (p.pulseRadius > 35) p.pulseRadius = 0;
      }
    }

    // Sort primarily for rendering points front-to-back
    const sortedParticles = [...particles].sort((a, b) => b.z - a.z);

    // Connect particles
    ctx.lineWidth = 1;
    for (let i = 0; i < sortedParticles.length; i++) {
      for (let j = i + 1; j < Math.min(i + 20, sortedParticles.length); j++) {
        const p1 = sortedParticles[i];
        const p2 = sortedParticles[j];

        const dx = p1.x - p2.x;
        const dy = p1.y - p2.y;
        const distSq = dx * dx + dy * dy;

        const maxDist = radius * 0.35;
        const maxDistSq = maxDist * maxDist;

        if (distSq < maxDistSq) {
          const depthFactor = (2 - (p1.z + p2.z)) / 4;
          const distFactor = 1 - distSq / maxDistSq;

          // If one of the points is a data source, highlight the trace route
          if ((p1.isSource || p2.isSource) && p1.z < 0 && p2.z < 0) {
            ctx.strokeStyle = `rgba(188, 140, 255, ${Math.max(0, depthFactor * distFactor * 0.8)})`;
            ctx.lineWidth = 1.5;
          } else {
            ctx.strokeStyle = `rgba(88, 166, 255, ${Math.max(0, depthFactor * distFactor * 0.4)})`;
            ctx.lineWidth = 1;
          }

          ctx.beginPath();
          ctx.moveTo(p1.x, p1.y);
          ctx.lineTo(p2.x, p2.y);
          ctx.stroke();
        }
      }
    }

    ctx.font =
      "12px ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace";

    // Draw Points & Sources
    for (let p of sortedParticles) {
      const depthFactor = (1 - p.z) / 2;
      const alpha = 0.1 + 0.9 * depthFactor;

      if (p.isSource && p.z < 0.2) {
        // Data Source glowing pin
        ctx.fillStyle = `rgba(188, 140, 255, ${alpha})`;
        ctx.beginPath();
        ctx.arc(p.x, p.y, Math.max(2, 4 * p.scale), 0, Math.PI * 2);
        ctx.fill();

        // Pulsing ring
        ctx.strokeStyle = `rgba(188, 140, 255, ${(1 - p.pulseRadius / 35) * alpha})`;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.pulseRadius * p.scale, 0, Math.PI * 2);
        ctx.stroke();

        // Tag Label (only when on front side)
        if (p.z < -0.4) {
          ctx.shadowColor = "#0d1117";
          ctx.shadowBlur = 4;
          ctx.fillStyle = `rgba(255, 255, 255, ${alpha})`;
          ctx.fillText(p.label, p.x + 8, p.y - 8);
          ctx.shadowBlur = 0; // reset
        }
      } else {
        // Regular network node
        if (depthFactor > 0.5) {
          ctx.fillStyle = `rgba(136, 192, 255, ${alpha})`;
          ctx.beginPath();
          ctx.arc(p.x, p.y, Math.max(0.5, 2 * p.scale), 0, Math.PI * 2);
          ctx.fill();
        } else {
          ctx.fillStyle = `rgba(48, 100, 180, ${alpha * 0.5})`;
          ctx.beginPath();
          ctx.arc(p.x, p.y, Math.max(0.5, 1 * p.scale), 0, Math.PI * 2);
          ctx.fill();
        }
      }
    }

    requestAnimationFrame(render);
  }

  render();
}
