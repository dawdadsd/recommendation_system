package xiaowu.backed.infrastructure.spark;

import static org.apache.spark.sql.functions.*;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryException;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.springframework.beans.factory.annotation.Value;
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
public class BehaviorStreamProcessor {

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

    // private final AtomicReference<StreamingQuery> runningQuery = new
    // AtomicReference<>();
    private enum State {
        STOPPED, STARTING, RUNNING, STOPPING
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);

    private final AtomicReference<StreamingQuery> runningQuery = new AtomicReference<>();

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
        Thread.ofVirtual()
                .name("spark-streaming-thread")
                .start(this::runStreamingJob);
        log.info("spark streaming 线程已提交，等待初始化");
    }

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
    }

    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    private void runStreamingJob() {
        SparkSession spark = SparkSession.builder()
                .appName(sparkAppName)
                .master(sparkMaster)
                .config("spark.ui.enabled", "false")
                .config("spark.sql.streaming.checkpointLocation", checkpointLocation)
                .config("spark.sql.adaptive.enabled", "false")
                .getOrCreate();

        spark.sparkContext().setLogLevel("WARN");
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

            StreamingQuery query = aggregated.writeStream()
                    .outputMode("update")
                    .format("console")
                    .option("truncate", "false")
                    .option("numRows", "20")
                    .trigger(org.apache.spark.sql.streaming.Trigger.ProcessingTime("10 seconds"))
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
}
