package xiaowu.backed.infrastructure.spark;

import static org.apache.spark.sql.functions.*;

import java.sql.DriverManager;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.spark.api.java.function.VoidFunction2;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.apache.spark.sql.streaming.Trigger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Spark Structured Streaming 流处理器 —— 双流水线架构
 *
 * <p>
 * 数据流拓扑：
 *
 * <pre>
 *   Kafka(user-events)
 *       │
 *       ▼
 *   KafkaEventDeserializer.deserialize()  ← 二进制 → 结构化 Row
 *       │
 *       ├──► buildWindowAggregation()     → foreachBatch → MySQL (行为报表)
 *       │
 *       └──► buildRecommendationScoreStream() → foreachBatch → Kafka (推荐结果)
 * </pre>
 *
 * <p>
 * 设计决策：
 * <ul>
 * <li>实现 SmartLifecycle 而非 InitializingBean：精确控制启停顺序，
 * 确保 Spark 在 Kafka/DB 之前关闭（phase = MAX_VALUE - 1）</li>
 * <li>状态机（STOPPED → STARTING → RUNNING → STOPPING）+ CAS：
 * 防止并发启停导致资源泄漏</li>
 * <li>双流水线共享同一个 parsed Dataset：Spark 内部会优化为单次 Kafka 读取，
 * 避免重复消费</li>
 * </ul>
 *
 * @author xiaowu
 */
@Slf4j
@Component
public class BehaviorStreamProcessor implements SmartLifecycle {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${kafka.topic.user-events}")
    private String userEventsTopic;

    @Value("${spark.app.name}")
    private String sparkAppName;

    @Value("${spark.master}")
    private String sparkMaster;

    @Value("${spark.sql.streaming.checkpointLocation}")
    private String checkpointLocation;

    @Value("${kafka.topic.recommendations}")
    private String recommendationsTopic;

    @Value("${recommendation.top-n:${recommendation.default.count:10}}")
    private int recommendationTopN;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    private enum State {
        STOPPED, STARTING, RUNNING, STOPPING
    }

    private final KafkaEventDeserializer deserializer;

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);

    /**
     * 为什么用 CopyOnWriteArrayList 而非普通 ArrayList？
     * activeQueries 由 streaming 线程写入（add），由 Spring 关闭线程读取（stop 遍历）。
     * CopyOnWriteArrayList 在读多写少场景下无锁读取，且写入时拷贝数组保证线程安全。
     * 这里最多只有 2 个 query，写入次数极少，完美匹配此数据结构。
     */
    private final List<StreamingQuery> activeQueries = new CopyOnWriteArrayList<>();

    /**
     * 为什么用 volatile 而非 AtomicReference？
     * streamingThread 只有两个操作：start() 写入、stop() 读取。
     * 不存在 CAS 竞争（state 的 CAS 已保证只有一个线程进入 start()），
     * volatile 仅需保证跨线程可见性，比 AtomicReference 更轻量、意图更清晰。
     */
    private volatile Thread streamingThread;

    /**
     * 为什么用 Text Block 定义 SQL 常量？
     * 1. 可读性：多行 SQL 一目了然，避免字符串拼接的噪音
     * 2. ON DUPLICATE KEY UPDATE：利用表上的唯一约束 (user_id, behavior_type, window_start,
     * window_end)
     * 实现幂等写入。Spark checkpoint 重放时不会产生重复行，只会更新已有记录。
     * 这比 SaveMode.Append 安全得多 —— Append 在故障恢复时会插入重复数据。
     */
    private static final String AGGREGATION_UPSERT_SQL = """
            INSERT INTO user_behavior_aggregation
                (user_id, behavior_type, window_start, window_end, event_count, avg_rating)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                event_count = VALUES(event_count),
                avg_rating  = VALUES(avg_rating)
            """;

    /**
     * 为什么用构造器注入而非 @Autowired 字段注入？
     * 1. final 语义：注入后不可变，防止运行时被意外覆盖
     * 2. 编译期检查：缺少依赖时编译报错，而非运行时 NPE
     * 3. 可测试性：单元测试可直接 new 传入 mock，无需 Spring 容器
     */
    BehaviorStreamProcessor(KafkaEventDeserializer deserializer) {
        this.deserializer = deserializer;
    }

    // ========================= SmartLifecycle 实现 =========================

    /**
     * 为什么用 CAS 而非 synchronized？
     * CAS 是无锁操作，不会阻塞线程。状态转换是简单的原子比较-交换，
     * 不需要 synchronized 的重量级锁机制。且 CAS 天然防止重复启动：
     * 只有从 STOPPED → STARTING 才成功，其他状态一律拒绝。
     *
     * 为什么设 daemon = true？
     * 这是最后的安全网。正常流程由 SmartLifecycle.stop() 优雅关闭。
     * 但万一 Spring 关闭逻辑出了问题，daemon 线程不会阻止 JVM 退出，避免进程僵死。
     */
    @Override
    public void start() {
        if (!state.compareAndSet(State.STOPPED, State.STARTING)) {
            log.warn("[Spark] current state={}, ignoring duplicate start", state.get());
            return;
        }
        streamingThread = new Thread(this::runStreamingJob, "spark-streaming-thread");
        streamingThread.setDaemon(true);
        streamingThread.start();
        log.info("[Spark] streaming thread submitted, awaiting initialization");
    }

    @Override
    public void stop() {
        if (!state.compareAndSet(State.RUNNING, State.STOPPING)) {
            log.warn("[Spark] current state={}, cannot stop", state.get());
            return;
        }
        for (var query : activeQueries) {
            if (query != null && query.isActive()) {
                try {
                    query.stop();
                    log.info("[Spark] query stopped: {}", query.name());
                } catch (TimeoutException e) {
                    log.warn("[Spark] query stop timeout: {}", e.getMessage());
                }
            }
        }
        activeQueries.clear();

        if (streamingThread != null && streamingThread.isAlive()) {
            streamingThread.interrupt();
            log.info("[Spark] streaming thread interrupted");
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    /**
     * 为什么返回 false？
     * 禁止 Spring 容器自动启动 Spark Streaming。
     * 由外部控制器（如 StreamSimulatorService）在数据源就绪后手动调用 start()，
     * 避免启动顺序竞争：Spark 必须在 Kafka topic 创建完成后才能订阅。
     */
    @Override
    public boolean isAutoStartup() {
        return false;
    }

    /**
     * 为什么用 Integer.MAX_VALUE - 1？
     * SmartLifecycle 的 phase 值越大，关闭越早。
     * Spark 依赖 Kafka 消费数据、依赖 MySQL 写入聚合，
     * 必须在 Kafka 和数据库连接池之前停止，否则关闭时 foreachBatch 会抛连接异常。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }

    // ========================= 流处理编排 =========================

    /**
     *
     * 为什么用 spark.streams().awaitAnyTermination() 而非 query.awaitTermination()？
     * 有两个 StreamingQuery 并行运行，需要等待任意一个终止。
     * 如果用单个 query.awaitTermination()，另一个 query 的异常会被忽略。
     */
    private void runStreamingJob() {
        var spark = createSparkSession();
        try {
            var parsed = readAndParseKafkaStream(spark);

            startAggregationQuery(buildWindowAggregation(parsed));
            startRecommendationQuery(buildRecommendationScoreStream(parsed));

            state.set(State.RUNNING);
            log.info("[Spark] {} streaming queries started, awaiting data...",
                    activeQueries.size());

            spark.streams().awaitAnyTermination();

        } catch (StreamingQueryException | TimeoutException e) {
            log.error("[Spark] streaming exception: {}", e.getMessage(), e);
        } finally {
            activeQueries.clear();
            state.set(State.STOPPED);
            spark.stop();
            log.info("[Spark] SparkSession closed");
        }
    }

    // ========================= 基础设施方法 =========================

    private SparkSession createSparkSession() {
        var spark = SparkSession.builder()
                .appName(sparkAppName)
                .master(sparkMaster)
                .config("spark.ui.enabled", "false")
                .config("spark.sql.streaming.checkpointLocation", checkpointLocation)
                .config("spark.sql.adaptive.enabled", "false")
                .getOrCreate();
        log.info("[Spark] SparkSession created, master={}", sparkMaster);
        return spark;
    }

    /**
     * 为什么将 Kafka 读取和反序列化放在一起？
     * 这两步是紧耦合的：读取产出的是 Kafka 原始 binary，
     * 必须立即反序列化才能被下游使用。
     * 反序列化逻辑委托给 KafkaEventDeserializer，
     * 如果未来消息格式变更（JSON → Avro），只改 Deserializer，不动 Processor。
     */
    private Dataset<Row> readAndParseKafkaStream(SparkSession spark) {
        var rawStream = spark.readStream()
                .format("kafka")
                .option("kafka.bootstrap.servers", bootstrapServers)
                .option("subscribe", userEventsTopic)
                .option("startingOffsets", "latest")
                .option("failOnDataLoss", "false")
                .load();
        return deserializer.deserialize(rawStream);
    }

    // ========================= 流水线 1：行为聚合 → MySQL =========================

    /**
     * 30 秒滚动窗口聚合：userId x behaviorType → eventCount + avgRating
     *
     * <p>
     * 为什么选 30 秒窗口 + 1 分钟 watermark？
     * - 30 秒窗口：粒度足够细，能捕捉用户短期行为爆发（如连续浏览），
     * 同时不会产生过多小批次写入数据库
     * - 1 分钟 watermark：允许最多 1 分钟的事件延迟到达（网络抖动、客户端缓存），
     * 超过 watermark 的迟到数据会被丢弃，保证窗口能及时关闭释放内存
     */
    private Dataset<Row> buildWindowAggregation(Dataset<Row> parsed) {
        return parsed
                .withWatermark("eventTime", "1 minute")
                .groupBy(
                        window(col("eventTime"), "30 seconds"),
                        col("userId"),
                        col("behaviorType"))
                .agg(
                        count("*").as("eventCount"),
                        avg("rating").as("avgRating"))
                .select(
                        col("window.start").as("windowStart"),
                        col("window.end").as("windowEnd"),
                        col("userId"),
                        col("behaviorType"),
                        col("eventCount"),
                        round(col("avgRating"), 2).as("avgRating"));
    }

    /**
     * 为什么用 VoidFunction2 显式强转？
     * Spark 的 foreachBatch 有两个重载：Scala Function2 和 Java VoidFunction2。
     * Java 编译器无法从 lambda 推断目标类型，导致 "ambiguous method" 编译错误。
     * 显式强转消除歧义，这是 Spark Java API 的标准用法。
     */
    private void startAggregationQuery(Dataset<Row> aggregated) throws TimeoutException {
        var query = aggregated.writeStream()
                .outputMode("update")
                .trigger(Trigger.ProcessingTime("10 seconds"))
                .option("checkpointLocation", checkpointLocation + "/aggregation")
                .foreachBatch(
                        (VoidFunction2<Dataset<Row>, Long>) this::publishAggregationBatch)
                .start();
        activeQueries.add(query);
        log.info("[Spark] aggregation query started → MySQL");
    }

    /**
     * 为什么用 foreachPartition + JDBC 手写 upsert，而不是 Spark JDBC Sink（SaveMode.Append）？
     *
     * <p>
     * 核心原因：幂等性。
     * Spark Structured Streaming 的 exactly-once 语义依赖 checkpoint。
     * 当 checkpoint 回滚重放时，SaveMode.Append 会插入重复行（同一窗口的聚合结果写两次）。
     * 而 ON DUPLICATE KEY UPDATE 利用表上的唯一约束，重复写入只更新不插入，保证幂等。
     *
     * <p>
     * 为什么捕获 JDBC 配置为局部变量？
     * Spark 执行 foreachPartition 的 lambda 时会序列化闭包。
     * 如果 lambda 引用 this.jdbcUrl，会尝试序列化整个 BehaviorStreamProcessor（Spring Bean），
     * 导致 NotSerializableException。捕获为 String 局部变量后，闭包只序列化几个 String，安全且轻量。
     *
     * <p>
     * 为什么用 addBatch + executeBatch 而非逐行 executeUpdate？
     * 批量提交减少数据库 round-trip 次数，一个 partition 只需一次网络往返。
     * 配合 conn.setAutoCommit(false) + commit()，保证同一批次原子提交。
     */
    private void publishAggregationBatch(Dataset<Row> batchDataset, long batchId) {
        if (batchDataset.isEmpty()) {
            return;
        }

        // 捕获为局部变量，避免序列化整个 Spring Bean
        var url = this.jdbcUrl;
        var user = this.dbUsername;
        var password = this.dbPassword;

        batchDataset.foreachPartition(rows -> {
            try (var conn = DriverManager.getConnection(url, user, password);
                    var stmt = conn.prepareStatement(AGGREGATION_UPSERT_SQL)) {

                conn.setAutoCommit(false);
                while (rows.hasNext()) {
                    var row = rows.next();
                    stmt.setLong(1, row.getAs("userId"));
                    stmt.setString(2, row.getAs("behaviorType"));
                    stmt.setTimestamp(3, row.getAs("windowStart"));
                    stmt.setTimestamp(4, row.getAs("windowEnd"));
                    stmt.setLong(5, row.getAs("eventCount"));

                    Double avgRating = row.getAs("avgRating");
                    if (avgRating != null) {
                        stmt.setDouble(6, avgRating);
                    } else {
                        stmt.setNull(6, Types.DOUBLE);
                    }
                    stmt.addBatch();
                }
                stmt.executeBatch();
                conn.commit();
            }
        });

        log.info("[Spark] batchId={} aggregation upserted to MySQL", batchId);
    }

    // ========================= 流水线 2：推荐评分 → Kafka =========================

    /**
     * 为什么将行为类型映射为数值评分？
     * 不同行为反映不同的用户偏好强度：
     * - VIEW/CLICK (1.0)：弱兴趣信号，用户只是浏览
     * - ADD_TO_CART (3.0)：中等兴趣，有购买意图
     * - PURCHASE (5.0)：强兴趣信号，已完成转化
     * - RATE：用户主动评分，归一化到 [0, 4.0] 区间（原始评分 1-5 减 1）
     *
     * 为什么用 greatest/least 做归一化而非 if-else？
     * Spark Column 表达式在 Catalyst 优化器中运行，不能使用 Java 控制流。
     * greatest(0, least(4, x-1)) 等价于 Math.max(0, Math.min(4, x-1))，
     * 将任意评分值钳制到 [0, 4.0] 范围，防止异常数据（如评分为 -1 或 100）污染推荐结果。
     */
    private Column buildPreferenceScoreColumn() {
        var normalizedRatingScore = greatest(
                lit(0.0),
                least(
                        lit(4.0),
                        coalesce(col("rating"), lit(1.0)).minus(lit(1.0))));

        return when(col("behaviorType").equalTo("VIEW"), lit(1.0))
                .when(col("behaviorType").equalTo("CLICK"), lit(1.0))
                .when(col("behaviorType").equalTo("ADD_TO_CART"), lit(3.0))
                .when(col("behaviorType").equalTo("PURCHASE"), lit(5.0))
                .when(col("behaviorType").equalTo("RATE"), normalizedRatingScore)
                .otherwise(lit(0.0));
    }

    /**
     * 推荐评分流水线：userId x itemId → 偏好总分
     *
     * <p>
     * 与 buildWindowAggregation 的区别：
     * - 聚合维度不同：这里按 itemId 聚合（"用户对哪个商品感兴趣"），
     * 聚合流水线按 behaviorType 聚合（"用户做了什么行为"）
     * - 输出目标不同：推荐结果写入 Kafka 供下游实时消费，聚合结果写入 MySQL 供报表查询
     * - 过滤条件：score > 0 排除无效行为，避免推荐用户没有正向交互的商品
     */
    private Dataset<Row> buildRecommendationScoreStream(Dataset<Row> parsed) {
        return parsed
                .withWatermark("eventTime", "1 minute")
                .withColumn("preferenceScore", buildPreferenceScoreColumn())
                .groupBy(
                        window(col("eventTime"), "30 seconds"),
                        col("userId"),
                        col("itemId"))
                .agg(round(sum("preferenceScore"), 2).as("score"))
                .filter(col("score").gt(lit(0.0)))
                .select(
                        col("window.start").as("windowStart"),
                        col("window.end").as("windowEnd"),
                        col("userId"),
                        col("itemId"),
                        col("score"));
    }

    private void startRecommendationQuery(Dataset<Row> scoreStream) throws TimeoutException {
        var query = scoreStream.writeStream()
                .outputMode("update")
                .trigger(Trigger.ProcessingTime("10 seconds"))
                .option("checkpointLocation", checkpointLocation + "/recommendations")
                .foreachBatch(
                        (VoidFunction2<Dataset<Row>, Long>) this::publishRecommendationBatch)
                .start();
        activeQueries.add(query);
        log.info("[Spark] recommendation query started → Kafka({})", recommendationsTopic);
    }

    /**
     * 为什么先取最新窗口再排名，而非直接全量排序？
     *
     * <p>
     * update 输出模式下，foreachBatch 可能包含多个窗口的更新。
     * 如果直接对全部数据排名，旧窗口的高分物品可能挤掉新窗口的结果，
     * 导致推荐结果"穿越时间"。
     *
     * <p>
     * 处理流程：
     * 1. latestWindowByUser：找到每个用户的最新窗口结束时间
     * 2. 过滤只保留最新窗口的数据
     * 3. rankingWindow：在最新窗口内按 score 降序、itemId 升序排名
     * （itemId 升序作为平分时的确定性排序，保证结果稳定）
     * 4. 取 Top-N 后按 userId 分组，组装为 JSON 写入 Kafka
     *
     * <p>
     * 为什么用 takeAsList(1).isEmpty() 而非 isEmpty()？
     * Spark Structured Streaming 的某些版本中 isEmpty() 可能触发全量计算，
     * takeAsList(1) 只取一行即可判断是否为空，性能更优。
     */
    private void publishRecommendationBatch(Dataset<Row> batchDataset, long batchId) {
        if (batchDataset.takeAsList(1).isEmpty()) {
            log.debug("[Spark] batchId={} no recommendation updates", batchId);
            return;
        }

        WindowSpec latestWindowByUser = Window.partitionBy("userId");
        WindowSpec rankingWindow = Window.partitionBy("userId", "windowStart", "windowEnd")
                .orderBy(col("score").desc(), col("itemId").asc());

        var latestWindowScores = batchDataset
                .withColumn("latestWindowEnd", max(col("windowEnd")).over(latestWindowByUser))
                .filter(col("windowEnd").equalTo(col("latestWindowEnd")))
                .drop("latestWindowEnd");

        var topNRows = latestWindowScores
                .withColumn("rank", row_number().over(rankingWindow))
                .filter(col("rank").leq(recommendationTopN));

        var kafkaRows = topNRows
                .groupBy("userId", "windowStart", "windowEnd")
                .agg(sort_array(
                        collect_list(
                                struct(
                                        col("rank").as("rank"),
                                        col("itemId").as("itemId"),
                                        round(col("score"), 2).as("score"))),
                        true).as("items"))
                .withColumn("generatedAt", current_timestamp())
                .select(
                        col("userId").cast("string").as("key"),
                        to_json(struct(
                                col("userId").as("userId"),
                                col("generatedAt").as("generatedAt"),
                                col("windowStart").as("windowStart"),
                                col("windowEnd").as("windowEnd"),
                                col("items").as("items"))).as("value"));

        kafkaRows.write()
                .format("kafka")
                .option("kafka.bootstrap.servers", bootstrapServers)
                .option("topic", recommendationsTopic)
                .save();

        log.info("[Spark] batchId={} recommendations written to topic={}", batchId, recommendationsTopic);
    }
}
