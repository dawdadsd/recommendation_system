# AI 画像推荐系统 -- 演进路线图

> 核心命题：什么情况下这个项目会获得更多 star？
>
> 答案：当别人 clone 项目，启动后能跟 AI 聊天，AI 能说出"我知道你喜欢什么"的时候。
> 一个能对话、能体现个性化的 demo，比一个完美但只输出日志的数据管道更吸引人。

---

## 一、现状分析

### 已实现

| 模块 | 状态 | 说明 |
| :--- | :--- | :--- |
| Kafka 事件采集 | 已完成 | 幂等生产者，LZ4 压缩，userId 分区有序 |
| Spark 实时聚合 | 已完成 | 30s 窗口，userId x behaviorType → MySQL |
| Spark 推荐评分 | 已完成 | 30s 窗口，userId x itemId → Kafka Top-N |
| DDD 领域模型 | 已完成 | Entity/Value Object/Aggregate/Domain Service |
| REST 生命周期 API | 已完成 | start/stop/status/event |
| 事件模拟器 | 已完成 | 100 用户 x 500 商品 x 5 行为类型 |
| 压力测试 | 已完成 | 200 线程并发 10 万事件 |

### 缺失的三层

**第一层：用户基础属性（静态画像）**

当前系统只有 `userId`，不知道用户是谁。需要：
- 性别、年龄段、地域
- 注册时间、活跃等级
- 偏好品类标签（用户自选 or 系统推断）

这是冷启动的基础 -- 新用户没有行为数据时，AI 只能靠这些信息来对话。

**第二层：行为沉淀（动态画像）**

30 秒窗口聚合只是"此刻在干什么"，缺的是"这个人一直以来是什么样的"。需要离线批处理层，定期把历史行为炼成画像标签：
- 过去 7 天 / 30 天的品类偏好 Top-5
- 行为模式（浏览多购买少 = 比价型，浏览少购买多 = 目标明确型）
- 活跃时段分布
- 购买力区间

这是 Lambda 架构的批处理层：现有实时层（Spark Streaming）+ 缺失的批处理层（Spark Batch）。

**第三层：画像到 AI 的桥梁**

画像不是直接丢给 GPT 的 JSON，而是要转化为结构化的 System Prompt：

```text
你正在和一位用户对话。以下是该用户的画像：
- 偏好品类：3C数码（手机、耳机）、运动户外
- 消费能力：中高端（客单价 2000-5000 元）
- 行为特征：浏览多购买少，属于比价型用户
- 近期兴趣：最近 7 天密集浏览折叠屏手机
- 活跃时段：晚间 20:00-23:00

请基于以上画像，以专业但亲和的语气与用户对话。
当用户询问推荐时，优先推荐中高端 3C 产品。
```

---

## 二、目标架构

```
┌─────────────────────────────────────────────────────┐
│                    数据采集层                         │
│  用户行为事件 ──► Kafka (user-events)                │
│  用户注册/更新 ──► Kafka (user-profiles)             │
│  商品信息变更 ──► Kafka (item-catalog)               │
└──────────────────────┬──────────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
   实时层(已有)     批处理层(P2)    存储层(P1)
   Spark Streaming  Spark Batch     MySQL + Redis
   30s窗口聚合      每日画像计算     用户画像表
   实时推荐评分      品类偏好沉淀     商品信息表
        │              │              │
        └──────────────┼──────────────┘
                       │
                       ▼
              ┌─────────────────┐
              │  画像服务层(P1)   │
              │  UserProfileAPI  │
              │  实时画像 + 离线  │
              │  画像合并输出     │
              └────────┬────────┘
                       │
            ┌──────────┼──────────┐
            ▼                     ▼
     形态 A: AI 对话(P1)      形态 B: 主动推送(P3)
     用户问 → 查画像 →        定时任务 → 查画像 →
     注入 System Prompt →     AI 生成推荐理由 →
     GPT 个性化回答            推送给用户
```

### 两种产品形态对比

| | 形态 A：用户问 AI | 形态 B：系统主动推 |
| :--- | :--- | :--- |
| 触发方 | 用户 | 系统 |
| 时效要求 | 实时（用户在等） | 异步（可以提前算好） |
| AI 输入 | 用户画像 + 用户当前问题 | 用户画像 + 商品库 |
| AI 输出 | 自然语言回答 | 推荐列表 + 推荐理由 |
| 优先级 | Phase 1 | Phase 3 |

---

## 三、分阶段实施计划

### Phase 1：AI 对话 + 简易画像（最小可用版）

> 目标：clone 下来 5 分钟跑起来，能和 AI 聊天，AI 能体现对用户偏好的理解。
> 这一步完成后，项目从"数据管道 demo"变成"可交互的 AI 推荐系统"。

#### 1.1 数据库建模

**user_profile 表（用户基础属性）**

```sql
CREATE TABLE user_profile (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL UNIQUE,
    nickname    VARCHAR(64),
    gender      VARCHAR(8),               -- MALE / FEMALE / UNKNOWN
    age_range   VARCHAR(16),              -- 18-24 / 25-34 / 35-44 / 45+
    region      VARCHAR(64),
    preference_tags VARCHAR(256),         -- 逗号分隔: "3C数码,运动户外,美妆"
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**item_catalog 表（商品信息）**

```sql
CREATE TABLE item_catalog (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id     BIGINT       NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    category    VARCHAR(64)  NOT NULL,    -- "手机", "耳机", "运动鞋"
    price       DECIMAL(10,2),
    tags        VARCHAR(256),             -- "折叠屏,旗舰,5G"
    description TEXT,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 1.2 画像服务

**UserProfileService**

职责：查 MySQL 拼装用户画像，合并静态属性 + 实时行为数据。

```
输入：userId
处理：
  1. 查 user_profile 表 → 基础属性（性别、年龄、偏好标签）
  2. 查 user_behavior_aggregation 表 → 近期行为统计（最近 N 个窗口）
  3. 查 item_catalog 表 → 用户最近交互的商品品类
  4. 合并为结构化画像对象 UserProfileSnapshot
输出：UserProfileSnapshot（静态属性 + 动态行为 + 品类偏好）
```

#### 1.3 AI 对话接口

**ChatController + ChatService**

```
POST /api/v1/chat
Body: { "userId": 1, "message": "给我推荐一款手机" }

处理流程：
  1. ChatService 接收消息
  2. 调用 UserProfileService.getProfileSnapshot(userId)
  3. 将画像转化为 System Prompt（模板化）
  4. 拼接用户消息，调用 GPT API
  5. 返回 AI 回复

Response: {
  "success": true,
  "data": {
    "reply": "根据你最近对折叠屏手机的关注，推荐你看看...",
    "recommendedItems": [...]
  }
}
```

#### 1.4 模拟数据初始化

为了"5 分钟体验"，需要提供：
- `data-init.sql`：预置 20 个用户画像 + 100 个商品
- 启动模拟器后，行为数据自动积累
- 用户可以立即开始和 AI 对话

#### 1.5 Phase 1 交付物

- [ ] `user_profile` 表 + Entity + Repository
- [ ] `item_catalog` 表 + Entity + Repository
- [ ] `UserProfileService`：画像拼装
- [ ] `PromptTemplateService`：画像 → System Prompt 转化
- [ ] `ChatController` + `ChatService`：AI 对话接口
- [ ] `data-init.sql`：模拟数据
- [ ] 更新 README：增加 AI 对话的使用说明

---

### Phase 2：批处理层，炼化长期画像

> 目标：从"30 秒窗口的即时画像"升级为"长期沉淀的用户画像"。

#### 2.1 Spark Batch Job

每天凌晨执行，读取 `user_behavior_aggregation` 历史数据，计算：

| 画像标签 | 计算逻辑 |
| :--- | :--- |
| 品类偏好 Top-5 | 按品类聚合行为次数，取 Top-5 |
| 消费力标签 | 基于购买行为关联的商品价格区间 |
| 行为模式 | 浏览/购买比率 → 比价型/目标型/冲动型 |
| 活跃时段 | 按小时聚合事件数，取 Top-3 时段 |
| 评分倾向 | 平均评分 → 严格型(<3.0) / 中立型 / 宽松型(>4.0) |

#### 2.2 画像标签表

```sql
CREATE TABLE user_profile_tags (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT      NOT NULL,
    tag_category   VARCHAR(32) NOT NULL,   -- "category_pref" / "purchase_power" / "behavior_pattern"
    tag_value      VARCHAR(128) NOT NULL,
    confidence     DOUBLE,                 -- 置信度 0.0 - 1.0
    computed_at    DATE         NOT NULL,
    UNIQUE KEY uk_user_tag_date (user_id, tag_category, tag_value, computed_at),
    INDEX idx_user_computed (user_id, computed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 2.3 画像服务升级

`UserProfileService` 合并三个数据源：
1. `user_profile` → 静态属性
2. `user_profile_tags` → 离线画像标签（批处理产出）
3. `user_behavior_aggregation` → 实时行为快照

优先级：实时行为 > 离线标签 > 静态属性。如果实时数据显示用户突然开始浏览母婴用品，即使离线标签还是"3C 爱好者"，也应该在对话中体现这个变化。

#### 2.4 Phase 2 交付物

- [ ] Spark Batch Job：每日画像计算
- [ ] `user_profile_tags` 表 + Entity
- [ ] `UserProfileService` 升级：三源合并
- [ ] 调度配置（Spring `@Scheduled` 或 Spark 独立提交）

---

### Phase 3：系统主动推送

> 目标：系统定期为每个用户生成个性化推荐列表 + AI 撰写的推荐理由，主动推送。

#### 3.1 推送流程

```
定时任务（每小时）
  │
  ▼
遍历活跃用户 → 查画像 → 匹配商品候选集
  │
  ▼
AI 生成推荐理由（批量调用 GPT）
  │
  ▼
写入推送队列 → WebSocket / 消息推送
```

#### 3.2 推荐理由生成

不是简单的"为你推荐"，而是 AI 生成的个性化文案：

```text
输入画像：偏好 3C 数码，近期浏览折叠屏手机，比价型用户
输入商品：某品牌折叠屏手机，价格 6999 元，近期降价 500 元

AI 输出：
"这款折叠屏手机最近降了 500 元，是近 30 天的最低价。
你之前看过同品牌的上一代，这款在铰链耐久度和续航上都有升级，
性价比比上一代发布价还高。"
```

#### 3.3 Phase 3 交付物

- [ ] 推送调度任务
- [ ] AI 推荐理由生成 Service
- [ ] WebSocket 推送通道
- [ ] 前端推送通知组件

---

## 四、技术选型决策

| 决策点 | 选择 | 原因 |
| :--- | :--- | :--- |
| AI 模型 | GPT-4o-mini | 性价比最高，推荐场景不需要最强推理能力 |
| 画像缓存 | Redis | 画像查询频率高，缓存 TTL 5 分钟，避免每次对话都查 MySQL |
| 批处理调度 | Spring @Scheduled | 项目规模不需要 Airflow/XXL-Job，保持简单 |
| 主动推送 | WebSocket | 已有 Spring Boot 基础，无需额外引入消息推送服务 |
| Prompt 模板 | 代码内模板 | 初期无需 Prompt 管理平台，硬编码即可，后续再抽象 |

---

## 五、开发优先级总结

```
Phase 1（当前）──► Phase 2 ──► Phase 3
  AI 对话 +          批处理        主动推送
  简易画像           长期画像      推荐理由

  核心价值：         核心价值：     核心价值：
  "能聊天，          "画像更准，    "不用问，
   能个性化"          AI 更懂你"    系统主动推"
```

**Phase 1 是 star 的分水岭。** 做完 Phase 1，项目就有了可交互的演示效果。Phase 2 和 Phase 3 是锦上添花。

---

## 六、差异化竞争力

与 GitHub 上现有推荐系统项目对比：

| 维度 | 现有项目（4年前） | 本项目 |
| :--- | :--- | :--- |
| 技术栈 | Spark 2.x / Kafka 旧版 | Spark 4.0 + Kafka 3.7 + Spring Boot 3.5 |
| 推荐算法 | 协同过滤 / ALS | 行为评分 + AI 画像增强 |
| 交互方式 | 无（只有离线计算） | AI 对话 + 主动推送 |
| 体验门槛 | 需要配置 Hadoop 集群 | 本地 `mvn spring-boot:run` 即可 |
| 可观测性 | 看日志 | 对话即见效果 |

**核心差异：别人的推荐系统是"算出来看的"，你的是"能聊天的"。**
