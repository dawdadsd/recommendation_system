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
    audit: {
      time: now(),
      title: "HR org validated",
      detail: "employee E10384 → OPS-CN · DIM routed",
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

  // Push audit
  pushAuditRow(scene.audit);

  // Pipeline step animation
  animatePipeline();

  // Progress bar
  restartProgress();

  // Mini stage sidebar chips sync
  $all(".sidebar-chip").forEach((c) => c.classList.remove("active"));
  const chipMap = { erp: 0, payment: 1, hr: 2 };
  const chips = $all(".sidebar-chip");
  if (chipMap[scene.key] !== undefined && chips[chipMap[scene.key]]) {
    chips[chipMap[scene.key]].classList.add("active");
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
      const idx = scenes.findIndex((s) => s.key === key);
      if (idx < 0) return;
      applyScene(idx);
      scheduleNext();
    });
  });
}

function bindAutoplayToggle() {
  const btn = $("[data-autoplay-toggle]");
  if (!btn) return;
  btn.addEventListener("click", () => setAutoplay(!state.autoplay));
}

/* ---- Init ---- */
document.addEventListener("DOMContentLoaded", () => {
  bindSidebarButtons();
  bindAutoplayToggle();
  applyScene(0);
  scheduleNext();
});
