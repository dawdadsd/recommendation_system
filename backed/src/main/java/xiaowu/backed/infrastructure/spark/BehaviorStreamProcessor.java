package xiaowu.backed.infrastructure.spark;

import static org.apache.spark.sql.functions.*;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.apache.spark.sql.streaming.Trigger;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Spark Structured Streaming 流处理器
 * 数据流：Kafka(user-events) → JSON 解析 → 30s 滚动窗口聚合 → 控制台输出
 *
 * @author xiaowu
 */
@Slf4j
@Component
public class BehaviorStreamProcessor implements SmartLifecycle {

    @Value("${spring.kafka.bootstrap-servers}")
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

    // private final AtomicReference<StreamingQuery> runningQuery = new
    // AtomicReference<>();
    private enum State {
        STOPPED, STARTING, RUNNING, STOPPING
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);

    private final AtomicReference<StreamingQuery> runningQuery = new AtomicReference<>();

    private volatile Thread streamingThread;

    private StructType buildEventSchema() {
        return new StructType()
                .add("eventId", DataTypes.StringType, false)
                .add("userId", DataTypes.LongType, false)
                .add("itemId", DataTypes.LongType, false)
                .add("behaviorType", DataTypes.StringType, false)
                .add("rating", DataTypes.DoubleType, true)
                .add("sessionId", DataTypes.StringType, true)
                .add("deviceInfo", DataTypes.StringType, true)
                .add("timestamp", DataTypes.StringType, false); // ISO-8601 字符串
    }

    /** 启动 Spark Structured Streaming（虚拟线程，非阻塞） */
    public void start() {
        // CAS原子操作 : 只有从STOPPED到STARTING才允许启动,其他状态都不允许
        if (!state.compareAndSet(State.STOPPED, State.STARTING)) {
            log.warn("Spark 当前状态  = {},忽略重复启动请求", state.get());
            return;
        }
        streamingThread = new Thread(this::runStreamingJob, "spark-streaming-thread");
        streamingThread.setDaemon(true);
        streamingThread.start();
        log.info("spark streaming 线程已提交，等待初始化");
    }

    /**
     * @doc 为什么用 volatile 而不是 AtomicReference？
     *      streamingThread 只有两个操作：start() 里写入、stop() 里读取。不需要
     *      CAS 语义（不存在竞争写入的场景，CAS 已经在 state
     *      上保证了只有一个线程进入 start()）。volatile
     *      保证可见性就够了，更轻量、意图更清晰。
     *      为什么设 daemon = true？
     *      这是最后的安全网。正常流程由 SmartLifecycle.stop() 优雅关闭。但万一
     *      Spring 关闭逻辑出了问题，daemon 线程不会阻止 JVM
     *      退出，避免进程僵死。
     */
    public void stop() {
        // 只有Running状态才能停止
        if (!state.compareAndSet(State.RUNNING, State.STOPPING)) {
            log.warn("Spark 当前状态 = {},无法执行停止", state.get());
            return;
        }
        StreamingQuery query = runningQuery.get();
        if (query != null && query.isActive()) {
            try {
                query.stop();
                log.info("Spark stream query 已停止");
            } catch (TimeoutException e) {
                log.warn("Spark 停止query超时 : {}", e.getMessage());
            }
        }
        if (streamingThread != null && streamingThread.isAlive()) {
            streamingThread.interrupt();
            log.info("Spark streaming 线程已中断");
        }
    }

    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    /**
     * spring容器启动时不会自动调用start方法,需要手动调用start方法来启动Spark
     * Streaming,以避免与StreamSimulatorService的启动顺序问题
     *
     * @return false 表示不自动启动,需要手动调用start方法
     */
    @Override
    public boolean isAutoStartup() {
        return false;
    }

    /**
     * 关闭顺序 : 值越大越先关闭
     * spark 在 kafka,database之前停止
     * spark依赖kafka消费数据,必须先停spark,在停kafka
     *
     * @return Integer.MAX_VALUE - 1 确保在大多数组件之后停止,但在一些特殊组件之前停止
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
        ;
    }

    private void runStreamingJob() {
        SparkSession spark = SparkSession.builder()
                .appName(sparkAppName)
                .master(sparkMaster)
                .config("spark.ui.enabled", "false")
                .config("spark.sql.streaming.checkpointLocation", checkpointLocation)
                .config("spark.sql.adaptive.enabled", "false")
                .getOrCreate();

        // spark.sparkContext().setLogLevel("WARN"); // Spark 4.0 + Spring Boot Logback
        // 冲突，由 Spring 统一管理日志级别
        log.info("[Spark] SparkSession 创建完成，master={}", sparkMaster);

        try {
            Dataset<Row> rawStream = spark.readStream()
                    .format("kafka")
                    .option("kafka.bootstrap.servers", bootstrapServers)
                    .option("subscribe", userEventsTopic)
                    .option("startingOffsets", "latest")
                    .option("failOnDataLoss", "false")
                    .load();

            StructType schema = buildEventSchema();
            Dataset<Row> parsed = rawStream
                    .selectExpr("CAST(value AS STRING) AS json_str")
                    .select(from_json(col("json_str"), schema).as("e"))
                    .select(
                            col("e.eventId"),
                            col("e.userId"),
                            col("e.itemId"),
                            col("e.behaviorType"),
                            col("e.rating"),
                            col("e.sessionId"),
                            col("e.deviceInfo"),
                            to_timestamp(col("e.timestamp")).as("eventTime"))
                    .filter(col("userId").isNotNull());

            // 30s 滚动窗口聚合：userId × behaviorType → eventCount + avgRating
            Dataset<Row> aggregated = parsed
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

            Dataset<Row> recommendationScoreStream = buildRecommendationScoreStream(parsed);

            StreamingQuery query = recommendationScoreStream.writeStream()
                    .outputMode("update")
                    .trigger(Trigger.ProcessingTime("10 seconds"))
                    .option("checkpointLocation", checkpointLocation + "/recommendations")
                    .foreachBatch((batchDataset, batchId) -> publishRecommendationBatch(batchDataset, batchId))
                    .start();

            runningQuery.set(query);
            state.set(State.RUNNING);
            log.info("[Spark] Streaming Query 已启动，等待数据...");

            query.awaitTermination();

        } catch (StreamingQueryException | TimeoutException e) {
            log.error("[Spark] Streaming 处理异常: {}", e.getMessage(), e);
        } finally {
            runningQuery.set(null);
            state.set(State.STOPPED);
            spark.stop();
            log.info("[Spark] SparkSession 已关闭");
        }
    }

    private Column buildPreferenceScoreColumn() {
        Column normalizedRatingScore = greatest(
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

    private void publishRecommendationBatch(Dataset<Row> batchDataset, long batchId) {
        if (batchDataset.takeAsList(1).isEmpty()) {
            log.debug("[Spark] batchId={} 没有推荐结果更新", batchId);
            return;
        }

        WindowSpec latestWindowByUser = Window.partitionBy("userId");
        WindowSpec rankingWindow = Window.partitionBy("userId", "windowStart", "windowEnd")
                .orderBy(col("score").desc(), col("itemId").asc());

        Dataset<Row> latestWindowScores = batchDataset
                .withColumn("latestWindowEnd", max(col("windowEnd")).over(latestWindowByUser))
                .filter(col("windowEnd").equalTo(col("latestWindowEnd")))
                .drop("latestWindowEnd");

        Dataset<Row> topNRows = latestWindowScores
                .withColumn("rank", row_number().over(rankingWindow))
                .filter(col("rank").leq(recommendationTopN));

        Dataset<Row> kafkaRows = topNRows
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

        log.info("[Spark] batchId={} 推荐结果已写入 topic={}", batchId, recommendationsTopic);
    }

}
