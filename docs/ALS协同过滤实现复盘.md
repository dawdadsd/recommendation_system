# ALS协同过滤实现复盘

> 关联实现文件：
> `backed/src/main/java/xiaowu/backed/infrastructure/spark/BehaviorStreamProcessor.java`
> `backed/src/main/java/xiaowu/backed/domain/recommendation/entity/UserItemPreference.java`
> `backed/src/main/java/xiaowu/backed/domain/recommendation/entity/UserItemPreferenceDelta.java`
> `backed/src/main/java/xiaowu/backed/domain/recommendation/entity/UserCfRecall.java`
> `backed/src/main/java/xiaowu/backed/application/service/PreferenceAggregationService.java`
> `backed/src/main/java/xiaowu/backed/interfaces/rest/RecommendationMaintenanceController.java`
> `backed/src/main/java/xiaowu/backed/infrastructure/persistence/recommendation/JdbcPreferenceAggregationRepository.java`
> `backed/src/main/java/xiaowu/backed/infrastructure/persistence/recommendation/JdbcRecommendationJobCheckpointRepository.java`
> `backed/src/main/resources/database/user_item_preference.sql`
> `backed/src/main/resources/database/user_item_preference_delta.sql`
> `backed/src/main/resources/database/user_cf_recall.sql`
> `backed/src/main/resources/database/recommendation_job_checkpoint.sql`

## 1. 背景与目标

本次开发的目标不是“让 AI 直接推荐商品”，而是先把协同过滤需要的训练链路打通，形成一条可持续演进的推荐底座：

1. 实时行为进入 Kafka。
2. Spark 对行为做窗口聚合和偏好打分。
3. 行为增量落地为可追溯、可幂等的训练原料。
4. 增量原料压实为训练矩阵。
5. 后续用 ALS 从训练矩阵产出 `user_cf_recall`。
6. 在线接口只读取训练结果，不在请求链路中实时训练。

核心原则有两个：

- AI 只做理解和重排，不直接承担主召回。
- 推荐训练数据必须可追溯、可重算、可治理，不能只依赖内存态或临时流。

## 2. 初始现状与问题判断

项目原始状态已经具备一部分基础能力：

- 有 Kafka 行为流和 Spark Streaming 处理器。
- 有 MySQL 商品表、用户画像表和行为聚合表。
- 有 Spring AI / OpenAI 客户端和聊天入口。
- 有 `spark-mllib` 依赖，具备接入 ALS 的前提。

但是离完整协同过滤链路还差关键一层：

- `BehaviorStreamProcessor` 已经能产出实时推荐分数流和行为聚合流，但没有稳定的 ALS 训练输入。
- `ChatService` 主要是把用户画像拼成 prompt 调模型，缺少候选召回服务边界。
- 没有 `user_item_preference`、`user_cf_recall` 这类稳定训练与结果表。
- 没有任务进度管理，无法保证增量汇总幂等执行。

因此，这次实现不是“补一个算法类”，而是补一整条训练数据链路。

## 3. 最终确定的分层方案

### 3.1 数据分层

本次链路最终拆成 3 层数据：

1. `user_item_preference_delta`
   作用：短周期增量账本，记录某个时间窗口内用户对商品新增了多少偏好分和交互次数。

2. `user_item_preference`
   作用：训练输入压缩表，按 `user_id + item_id` 维护累计偏好值，是 ALS 的直接输入。

3. `user_cf_recall`
   作用：ALS 训练输出表，保存每个用户的候选商品结果和模型版本。

### 3.2 处理分层

对应处理逻辑也拆成 3 层：

1. Spark Streaming
   负责把原始行为实时转换为偏好增量，不负责长期训练矩阵累计。

2. Aggregation Service
   负责把 `delta` 按 checkpoint 压实到 `user_item_preference`。

3. ALS Trainer
   后续负责读取 `user_item_preference`，训练模型并写 `user_cf_recall`。

### 3.3 架构原则

本次几次关键设计原则如下：

- 流处理负责“增量事实”，不直接维护“长期真值”。
- 长期训练矩阵通过批量汇总得到，而不是流式无界累计。
- 重型聚合和 upsert 用 SQL 做集合运算，不用 JPA 逐条 save。
- SQL 可以使用，但要放在 Infrastructure Repository，不写在 Application Service 中。

## 4. 实现过程中的关键决策与重构轨迹

### 4.1 先补训练输入与召回结果实体

首先补齐了推荐域的基础实体与表结构：

- `UserItemPreference`
- `UserItemPreferenceDelta`
- `UserCfRecall`

表结构上明确了：

- `user_item_preference` 使用 `user_id + item_id` 唯一键。
- `user_item_preference_delta` 使用 `user_id + item_id + window_start + window_end` 唯一键。
- `user_cf_recall` 使用 `model_version + user_id + item_id` 唯一键。

这样做的目的：

- 让训练输入与训练输出都有明确的业务主键。
- 为幂等 upsert 提供数据库约束。
- 为后续版本回滚和清理提供基础条件。

### 4.2 发现“直接流式累计 user_item_preference”是错误方向

中间曾经尝试过一版方案：

- 在 Spark 中按 `userId + itemId` 做长期累计聚合。
- 直接把累计值 upsert 到 `user_item_preference`。

这版后来被主动回退，原因有两个：

1. 重启后累计值不可信
   因为流式累计依赖运行态和 checkpoint。如果 checkpoint 丢失、路径变更或流重建，新的累计值会从一个更晚的时间点开始，下一次 upsert 会把原来的长期累计值覆盖掉。

2. 状态无界增长
   `groupBy(userId, itemId)` 的流式长期累计会把 Spark state store 持续做大。随着用户商品对增加，状态会越来越重，最终会成为稳定性问题。

这个阶段是本次实现里最重要的一次“纠偏”。

得到的结论是：

- `user_item_preference` 不能直接由流式长期累计维护。
- 必须引入中间账本 `user_item_preference_delta`。

### 4.3 改为“窗口增量账本”方案

最终在 `BehaviorStreamProcessor` 中采用了更稳的方案：

1. 原始行为按窗口聚合。
2. 每个窗口内计算一次 `score_delta` 和 `interaction_delta`。
3. 以 `(user_id, item_id, window_start, window_end)` 为幂等键落到 `user_item_preference_delta`。

这样做的收益是：

- 同一个窗口重复写入不会产生重复事实。
- 行为历史可追溯、可重放。
- 后续可以按天、按周、按最近 30 天重建训练矩阵。
- 可以支持时间衰减和数据治理。

## 5. 本次开发中遇到的主要问题与处理方式

### 5.1 OpenAI 客户端 Bean 启动失败

现象：

- 应用启动时报 `BeanCreationException`
- `openAiClient` 创建失败

根因：

- `OpenAiClient` 读取的是 `ai.api-key`、`ai.base-url`
- 配置文件中同时存在 `spring.ai.openai.*` 和顶层 `ai.*`
- 两套配置语义重复，容易错配

处理思路：

- 明确只保留一套有效配置来源。
- 推荐统一到一套命名空间，避免 Bean 初始化时读取不到值。

教训：

- 外部客户端配置不要保留两套平行配置。
- 如果项目已经接入 Spring AI，就要避免和自定义客户端配置相互打架。

### 5.2 Spark 在 Java 25 下启动失败

现象：

- Spark 启动时报 `UnsupportedOperationException: getSubject is not supported`

根因：

- 实际运行时是 Java 25
- Spark 4.0.0 依赖的 Hadoop / 安全上下文链路在该版本下不兼容
- 项目 `pom.xml` 目标版本实际是 Java 17

处理方式：

- 将运行时 JDK 切回 Java 17
- 保持编译和运行版本一致

结论：

- Spark 运行时版本必须和其支持矩阵保持一致。
- Java 版本不是“小问题”，它是运行时约束的一部分。

### 5.3 `created_at` 默认值缺失导致 Spark JDBC batch insert 失败

现象：

- Spark 落地 `user_item_preference_delta` 时，MySQL 报：
  `Field 'created_at' doesn't have a default value`

根因：

- Spark JDBC 插入没有带 `created_at`
- 数据库真实表结构和 SQL 文件预期不一致
- JPA 的 `@PrePersist` 对原生 JDBC 写入不生效

处理方式：

- 校正真实表结构，确保 `created_at` 有默认值，或在插入中显式传值
- 意识到 `resources/database/*.sql` 并不会自动替代真实数据库结构

教训：

- JPA 实体上的生命周期回调，只对 JPA 生效，不对 Spark JDBC 生效。
- 流式写数据库时，必须以真实表结构为准，而不是以代码里的实体想象为准。

### 5.4 Service 中直接堆 SQL 不规范

最初版本把汇总 SQL 直接写在 `PreferenceAggregationService` 中。

这带来两个问题：

- Application Service 同时承担业务编排和持久化细节，职责混杂。
- 后续难以测试、难以替换、难以扩展不同持久化实现。

因此做了重构：

- 抽出 `PreferenceAggregationRepository`
- 抽出 `RecommendationJobCheckpointRepository`
- 在 `infrastructure/persistence/recommendation` 下落 JDBC 实现
- `PreferenceAggregationService` 只保留流程编排

这是本次实现里一个重要的工程质量提升点。

### 5.5 对“表会不会无限增大”的担忧

这个担忧是正确的，而且必须在设计阶段就回答。

分析结果：

- `user_item_preference_delta` 一定会持续增长，因为它是增量账本。
- `user_item_preference` 也会随着新用户商品对增长而变大。
- `user_cf_recall` 如果保留多版 `model_version`，同样会增长。

处理原则：

- `delta` 负责短期历史，不承担永久存储。
- `preference` 负责训练输入，需要按窗口重建或裁剪。
- `recall` 结果表只保留少量版本。

这直接推动了后面数据生命周期治理的设计。

## 6. 已落地的实现内容

### 6.1 Spark 侧

在 `BehaviorStreamProcessor` 中，当前已经落地：

- 行为聚合流 -> `user_behavior_aggregation`
- 偏好增量流 -> `user_item_preference_delta`
- 实时推荐分数流 -> Kafka `recommendations`

其中偏好分规则已明确：

- `VIEW = 1.0`
- `CLICK = 1.0`
- `ADD_TO_CART = 3.0`
- `PURCHASE = 5.0`
- `RATE = normalized rating`

### 6.2 数据库侧

已补齐以下表：

- `user_item_preference_delta`
- `user_item_preference`
- `user_cf_recall`
- `recommendation_job_checkpoint`

### 6.3 汇总侧

已补齐：

- `PreferenceAggregationService`
- `PreferenceAggregationRepository`
- `RecommendationJobCheckpointRepository`
- `JdbcPreferenceAggregationRepository`
- `JdbcRecommendationJobCheckpointRepository`
- `RecommendationMaintenanceController`

目前已经能通过显式触发的方式，把 `delta` 汇总进 `user_item_preference`。

## 7. 为什么汇总层保留原生 SQL

这里需要特别说明一个容易误解的问题。

“手写 SQL”本身不是不规范，关键看它处在什么层，以及解决的是什么问题。

这里保留原生 SQL 的理由是：

1. 这是典型的集合运算
   需要按区间聚合、`GROUP BY`、`SUM`、`MIN`、`MAX`、`ON DUPLICATE KEY UPDATE`

2. JPA 不适合做这类高效批量 upsert
   如果用 JPA，会变成查很多行到内存、Java 聚合、逐条更新，性能和正确性都不占优。

3. 但 SQL 不能放在 Application Service
   所以最终采取的是：
   Service 编排流程，Infrastructure Repository 持有 SQL

这是一种“保留性能，同时保证分层清晰”的折中方案。

## 8. 数据生命周期治理方案

这是这条链路后续必须补齐的部分。

### 8.1 `user_item_preference_delta`

定位：

- 短期增量账本
- 支持追溯、重算、时间衰减

治理建议：

- 只保留最近 7 到 30 天
- 定时汇总后删除更老数据
- 必要时归档到历史库或对象存储

### 8.2 `user_item_preference`

定位：

- ALS 训练输入压缩表

治理建议：

- 不做永久全历史累加
- 建议按最近 30/60/90 天重建一次
- 删除长期不活跃且分数很低的用户商品对

### 8.3 `user_cf_recall`

定位：

- ALS 训练输出结果表

治理建议：

- 只保留当前版本和前一版本
- 通过 `model_version` 做灰度与回滚
- 清理更老版本

## 9. 后续完整链路该如何收尾

当前实现还没有真正完成 ALS 训练闭环，后续推荐按下面顺序推进：

1. 在 `PreferenceAggregationService` 之上增加定时调度
   自动定时汇总 delta，而不是只靠手动接口触发。

2. 增加 delta 清理任务
   解决短期账本持续增长的问题。

3. 实现 ALS Trainer
   从 `user_item_preference` 读取训练矩阵，使用 Spark MLlib ALS 训练模型。

4. 写入 `user_cf_recall`
   训练后为每个用户产出 TopN 候选结果，并带上 `model_version`。

5. 增加在线查询服务
   独立于 `ChatService`，提供协同过滤结果查询接口。

6. 最后接入总召回层和 AI 重排
   把协同过滤召回、热门召回、近期行为召回、内容召回融合，再交给 AI 做解释和重排。

## 10. 本次复盘里最值得记住的工程经验

### 10.1 先分清“事实”和“结果”

- `delta` 是事实
- `preference` 是训练输入结果
- `recall` 是召回结果

如果把三者混在一张表里，后续会越来越难维护。

### 10.2 流处理不要直接维护长期真值

Spark Streaming 很适合写“窗口增量事实”，不适合在当前项目这个阶段直接承担“长期训练矩阵真值维护”。

### 10.3 发现设计方向错了，要尽早回退

本次最重要的一次优化不是“加了新功能”，而是及时否定了“直接流式累计 `user_item_preference`”这条错误路径。

工程质量不是不断往前堆代码，而是能识别哪些实现方式从根上就是错的。

### 10.4 批量聚合用集合运算，不要执着全 JPA

JPA 有适用边界。
像 checkpoint + range aggregation + upsert 这种场景，原生 SQL 更合适，但要放到正确的基础设施层。

### 10.5 数据表设计要在开发初期就考虑生命周期

如果一开始不考虑：

- 谁负责增量
- 谁负责压实
- 谁负责清理
- 谁负责版本化

后面只会越做越乱。

## 11. 当前状态总结

本次实现完成的不是“最终推荐系统”，而是协同过滤链路里最关键的底座部分：

- 实时偏好增量已经可落地
- 增量到训练矩阵的汇总链路已经建立
- 分层结构已经从“Service 堆 SQL”重构为“Service 编排 + Infrastructure 持久化”
- 关键环境问题与运行时问题已经定位清楚

接下来真正的主线只剩两步：

1. 做 ALS 训练输出 `user_cf_recall`
2. 做生命周期治理和在线读取

一旦这两步补齐，这条协同过滤链路就可以真正接入推荐主流程。

## Open Questions / Uncertainties

- `OpenAiClient` 配置命名目前是否已经统一，还需要在代码和配置层进一步收口。
- `BehaviorStreamProcessor` 里偏好增量插入 `created_at` 的真实修复方式，需以真实数据库表结构和运行版本为准。
- ALS 训练触发方式目前仍处于规划阶段，尚未完成调度、模型版本管理和结果回写。
