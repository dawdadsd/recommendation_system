# Recommendation System

基于 `Spring Boot 3.5 + Kafka + Spark 4.0 + MySQL + Spring AI` 的推荐系统原型，目前已经从“单体实时推荐 Demo”演进为：

- 在线服务：负责行为采集、实时流处理、用户画像、AI 对话、ALS 结果读取
- 训练服务：负责独立 ALS 协同过滤训练、版本切换、召回结果落库

## 当前完成度

当前项目已经完成了**推荐底座主链路**，可以认为完成度大致在 **70%~80%**，其中：

- 已完成：实时行为采集与 Spark Streaming 聚合
- 已完成：用户商品偏好增量账本 `user_item_preference_delta`
- 已完成：增量汇总到训练矩阵 `user_item_preference`
- 已完成：ALS 独立训练服务 `recommendation-trainer`
- 已完成：ALS 结果表 `user_cf_recall`
- 已完成：模型版本表 `recommendation_model_version`
- 已完成：在线读取 ALS 召回结果并补齐商品信息
- 已完成：基础清理任务与聚合任务
- 已完成：AI 画像对话推荐链路
- 未完成：多路召回融合
- 未完成：排序/重排模型
- 未完成：完整训练评估体系
- 未完成：结果版本清理与回滚自动化

## 本次已经落地的核心更新

### 1. 推荐链路从 Demo 变成了可训练结构

现在推荐链路不再只是：

- Kafka 行为流
- Spark 实时 TopN
- AI 直接输出推荐

而是已经具备了标准推荐系统的训练结构：

1. 行为事件进入 Kafka
2. Spark 将行为转成窗口偏好增量
3. 增量落到 `user_item_preference_delta`
4. 定时任务汇总成 `user_item_preference`
5. ALS 训练生成 `user_cf_recall`
6. 在线服务读取当前模型版本的召回结果

### 2. ALS 训练已从在线服务中拆出

这是本次最重要的架构升级。

之前 ALS 训练放在在线服务里执行，会和：

- Spring Web 容器
- Spark Streaming
- 同一个 SparkContext

产生运行时冲突。

现在已经拆成两个独立模块：

- `backed`：在线服务
- `recommendation-trainer`：训练服务

这样做的收益：

- 训练和实时流处理的 SparkContext 完全隔离
- 训练失败不会拖垮在线服务
- 后续独立部署、独立扩缩容更容易

### 3. 在线读取 ALS 召回已经打通

现在在线服务可以读取：

- 当前生效模型版本
- 当前用户的 ALS TopN 召回结果
- 商品详情补齐
- 过滤用户已强交互商品

相关能力已经落在：

- `RecallController`
- `UserRecallService`
- `ModelVersionReadRepository`

## 模块说明

### `backed`

在线服务模块，负责：

- Web API
- Kafka 事件采集
- Spark Streaming 实时聚合
- 偏好增量落库
- `delta -> preference` 汇总
- 清理任务
- AI 画像对话
- ALS 召回结果读取

### `recommendation-trainer`

训练服务模块，负责：

- 独立 Spark ALS 训练
- 从 `user_item_preference` 读取训练矩阵
- 生成 `user_cf_recall`
- 切换 `recommendation_model_version`

## 当前已实现能力

### 在线服务

- `POST /api/stream/start?eventsPerSecond=<n>`
  启动事件模拟和 Spark Streaming

- `POST /api/stream/stop`
  停止流处理

- `GET /api/stream/status`
  查询运行状态

- `POST /api/stream/event`
  手动发送一条行为事件

- `POST /api/chat?userId=<id>`
  基于用户画像进行 AI 对话推荐

- `POST /api/recommendation/maintenance/aggregate-preferences`
  手动执行 `delta -> preference` 汇总

- `POST /api/recommendation/maintenance/cleanup-preference-delta`
  手动执行偏好增量清理

- `GET /api/recommendation/recall/{userId}?limit=20`
  读取当前模型版本下的 ALS 召回结果并补齐商品信息

### 训练服务

- `POST /api/trainer/als/train`
  手动触发 ALS 训练

训练完成后会：

1. 读取 `user_item_preference`
2. 训练 ALS 模型
3. 生成新的 `model_version`
4. 写入 `user_cf_recall`
5. 更新 `recommendation_model_version`

## 数据表说明

### 已完成

- `user_behavior_aggregation`
  实时行为窗口聚合表

- `user_item_preference_delta`
  用户商品偏好增量账本

- `user_item_preference`
  ALS 训练输入矩阵

- `user_cf_recall`
  ALS 训练输出结果

- `recommendation_job_checkpoint`
  聚合任务处理进度

- `recommendation_model_version`
  当前生效模型版本

- `user_profile`
  用户静态画像

- `item_catalog`
  商品目录

## 项目结构

```text
recommendation_system/
├── pom.xml                          # 父工程
├── backed/                          # 在线服务
├── recommendation-trainer/          # ALS训练服务
└── docs/                            # 设计与复盘文档
```

### 在线模块重点类

- `xiaowu.backed.Application`
- `BehaviorStreamProcessor`
- `PreferenceAggregationService`
- `PreferenceDeltaCleanUpService`
- `RecommendationJobScheduler`
- `ChatController`
- `RecallController`
- `UserRecallService`

### 训练模块重点类

- `xiaowu.trainer.TrainerApplication`
- `AlsTrainingService`
- `AlsTrainingController`
- `JdbcUserCfRecallWriteRepository`
- `JdbcRecommendationModelVersionRepository`

## 如何启动

### 1. 准备 MySQL

```bash
docker run -d \
  --name mysql \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=recommendation \
  --restart unless-stopped \
  mysql:8.4
```

### 2. 初始化数据库

至少需要执行：

- `user_profile.sql`
- `item_catalog.sql`
- `user_behavior_aggregation.sql`
- `user_item_preference_delta.sql`
- `user_item_preference.sql`
- `user_cf_recall.sql`
- `recommendation_job_checkpoint.sql`
- `recommendation_model_version.sql`
- `data-init.sql`

### 3. 启动在线服务

```bash
mvn -pl backed -DskipTests spring-boot:run
```

默认端口：

- `8922`

### 4. 启动训练服务

```bash
mvn -pl recommendation-trainer -DskipTests spring-boot:run
```

默认端口：

- `8923`

## 推荐链路说明

### 实时链路

1. 行为事件进入 Kafka
2. Spark Streaming 聚合窗口行为
3. 行为报表写入 `user_behavior_aggregation`
4. 偏好增量写入 `user_item_preference_delta`

### 训练链路

1. 聚合任务把 `user_item_preference_delta` 压实到 `user_item_preference`
2. 训练服务从 `user_item_preference` 读取训练样本
3. ALS 训练输出 `user_cf_recall`
4. 版本表切换当前生效模型

### 在线召回链路

1. 查询当前生效 `model_version`
2. 读取用户在该版本下的 `user_cf_recall`
3. 补齐商品信息
4. 过滤已强交互商品
5. 返回候选商品列表

## 目前支持的推荐方式

### 已支持

- 基于用户画像的 AI 对话推荐
- 基于 ALS 的协同过滤召回
- 基于实时行为的偏好增量沉淀

### 下一步支持

- 热门召回
- 近期行为召回
- 内容相似召回
- 多路召回融合
- 排序模型 / 重排模型
- AI 解释层
- 模型版本清理
- 召回结果版本回滚

## 后续规划

### 短期

- 增加 ALS 训练状态接口
- 增加训练中互斥保护
- 增加 `user_cf_recall` 历史版本清理
- 增加 trainer 模块健康检查

### 中期

- 引入热门召回与近期行为召回
- 做多路召回融合
- 引入排序层
- 引入模型效果评估指标

### 长期

- 将在线服务与训练服务独立部署
- 引入任务平台调度训练
- 引入特征治理、实验平台和 A/B Test

## 运行注意事项

- Spark 运行时请使用 **JDK 17**
- 在线服务和训练服务不要共用一个 SparkContext
- AI Key 建议改为环境变量，不要直接写入配置文件
- `user_item_preference_delta`、`user_item_preference`、`user_cf_recall` 都需要生命周期治理
