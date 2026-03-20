package xiaowu.trainer.application.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.spark.ml.recommendation.ALS;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.trainer.application.dto.AlsTrainingResultDTO;
import xiaowu.trainer.application.dto.AlsTrainingStatusDTO;
import xiaowu.trainer.application.exception.TrainingAlreadyRunningException;
import xiaowu.trainer.domain.recommendation.repository.RecommendationModelVersionRepository;
import xiaowu.trainer.domain.recommendation.repository.UserCfRecallWriteRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlsTrainingService {

        @Value("${spring.datasource.url}")
        private String jdbcUrl;

        @Value("${spring.datasource.username}")
        private String dbUsername;

        @Value("${spring.datasource.password}")
        private String dbPassword;

        @Value("${spark.app.name}")
        private String sparkAppName;

        @Value("${spark.master}")
        private String sparkMaster;

        @Value("${recommendation.als.model-name:user-cf-als}")
        private String modelName;

        @Value("${recommendation.als.rank:20}")
        private int rank;

        @Value("${recommendation.als.max-iter:10}")
        private int maxIter;

        @Value("${recommendation.als.reg-param:0.05}")
        private double regParam;

        @Value("${recommendation.als.alpha:20.0}")
        private double alpha;

        @Value("${recommendation.als.top-k:50}")
        private int topK;

        @Value("${recommendation.als.training-window-days:90}")
        private int trainingWindowDays;

        @Value("${recommendation.als.min-interaction-count:1}")
        private long minInteractionCount;

        @Value("${recommendation.als.min-preference-score:0.1}")
        private double minPreferenceScore;

        private final RecommendationModelVersionRepository recommendationModelVersionRepository;
        private final UserCfRecallWriteRepository userCfRecallWriteRepository;
        private final AtomicBoolean trainingRunning = new AtomicBoolean(false);

        private volatile TrainingStatus status = TrainingStatus.idle();

        private final String TRAINING_SQL = """
                        (SELECT user_id, item_id, preference_score, interaction_count
                         FROM user_item_preference
                         WHERE last_window_end >= DATE_SUB(NOW(), INTERVAL %d DAY)
                           AND interaction_count >= %d
                           AND preference_score >= %s) preference_training
                        """.formatted(trainingWindowDays, minInteractionCount, minPreferenceScore);

        public AlsTrainingResultDTO trainAndPublish() {
                if (!trainingRunning.compareAndSet(false, true)) {
                        throw new TrainingAlreadyRunningException(
                                        "ALS training is already running");
                }
                String modelVersion = buildModelVersion();
                String previousVersion = recommendationModelVersionRepository
                                .findCurrentVersion(modelName)
                                .orElse(null);

                SparkSession spark = null;
                LocalDateTime startTime = LocalDateTime.now();
                status = TrainingStatus.running(previousVersion, startTime, "LOADING_DATE", "Loading training data");
                try {
                        spark = createTrainingSparkSession();
                        var preferenceDf = spark.read()
                                        .format("jdbc")
                                        .option("url", jdbcUrl)
                                        .option("dbtable", TRAINING_SQL)
                                        .option("user", dbUsername)
                                        .option("password", dbPassword)
                                        .option("driver", "com.mysql.cj.jdbc.Driver")
                                        .load()
                                        .selectExpr(
                                                        "CAST(user_id AS INT) AS userId",
                                                        "CAST(item_id AS INT) AS itemId",
                                                        "CAST(preference_score AS FLOAT) AS rating");

                        long userCount = preferenceDf.select("userId").distinct().count();
                        if (userCount == 0) {
                                throw new IllegalStateException("No training data available for ALS");
                        }
                        status = TrainingStatus.running(modelVersion, startTime, "FITTING_MODEL",
                                        "Fitting ALS model");
                        ALS als = new ALS()
                                        .setUserCol("userId")
                                        .setItemCol("itemId")
                                        .setRatingCol("rating")
                                        .setRank(rank)
                                        .setMaxIter(maxIter)
                                        .setRegParam(regParam)
                                        .setAlpha(alpha)
                                        .setImplicitPrefs(true)
                                        .setColdStartStrategy("drop")
                                        .setNonnegative(true);
                        var model = als.fit(preferenceDf);
                        var recommendationDf = breakLineage(spark, model.recommendForAllUsers(topK));
                        long recallRowCount = userCfRecallWriteRepository.replaceModelRecall(modelVersion,
                                        recommendationDf);
                        status = TrainingStatus.running(modelVersion, startTime, "WRITING_RECALL",
                                        "Writing recall rows");
                        recommendationModelVersionRepository.switchVersion(
                                        modelName,
                                        modelVersion,
                                        previousVersion,
                                        "ACTIVE");
                        status = TrainingStatus.succeeded(
                                        modelVersion,
                                        startTime,
                                        LocalDateTime.now(),
                                        "ALS training finished successfully");
                        log.info(
                                        "[ALS-Training] finished, modelName={}, modelVersion={}, previousVersion={}, userCount={}, recallRowCount={}",
                                        modelName,
                                        modelVersion,
                                        previousVersion,
                                        userCount,
                                        recallRowCount);

                        return new AlsTrainingResultDTO(
                                        modelName,
                                        modelVersion,
                                        previousVersion,
                                        topK,
                                        userCount,
                                        recallRowCount,
                                        true);
                } catch (Exception e) {
                        status = TrainingStatus.failed(
                                        modelVersion,
                                        startTime,
                                        LocalDateTime.now(),
                                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        throw e;
                } finally {
                        if (spark != null) {
                                spark.stop();
                        }
                        trainingRunning.set(false);
                }
        }

        public AlsTrainingStatusDTO getStatus() {
                String activeVersion = recommendationModelVersionRepository
                                .findCurrentVersion(modelName)
                                .orElse(null);
                return status.toDto(modelName, activeVersion);
        }

        private SparkSession createTrainingSparkSession() {
                return SparkSession.builder()
                                .appName(sparkAppName + "-als-training")
                                .master(sparkMaster)
                                .config("spark.ui.enabled", "false")
                                .config("spark.sql.adaptive.enabled", "false")
                                .config("spark.driver.host", "127.0.0.1")
                                .config("spark.driver.bindAddress", "127.0.0.1")
                                .config("spark.driver.extraJavaOptions", "-Xss4m")
                                .config("spark.executor.extraJavaOptions", "-Xss4m")
                                .getOrCreate();
        }

        private String buildModelVersion() {
                return "als_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        }

        /**
         * 通过写入临时 Parquet 文件再读回来彻底截断 Spark DAG lineage，
         * 解决 recommendForAllUsers 产生的深层执行计划导致 StackOverflowError。
         */
        private Dataset<Row> breakLineage(SparkSession spark, Dataset<Row> df) {
                Path tempDir;
                try {
                        tempDir = Files.createTempDirectory("als-lineage-break-");
                } catch (IOException e) {
                        throw new UncheckedIOException(e);
                }
                String parquetPath = tempDir.resolve("data").toString();
                try {
                        df.write().parquet(parquetPath);
                        return spark.read().parquet(parquetPath);
                } finally {
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                                try {
                                        deleteRecursively(tempDir);
                                } catch (IOException ignored) {
                                }
                        }));
                }
        }

        private static void deleteRecursively(Path root) throws IOException {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                Files.delete(file);
                                return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                                Files.delete(dir);
                                return FileVisitResult.CONTINUE;
                        }
                });
        }

        private record TrainingStatus(
                        AlsTrainingStatusDTO.State state,
                        String trainingVersion,
                        String step,
                        LocalDateTime startedAt,
                        LocalDateTime finishedAt,
                        String message) {

                private static TrainingStatus idle() {
                        return new TrainingStatus(
                                        AlsTrainingStatusDTO.State.IDLE,
                                        null,
                                        "IDLE",
                                        null,
                                        null,
                                        "ALS training has not started yet");
                }

                private static TrainingStatus running(
                                String trainingVersion,
                                LocalDateTime startedAt,
                                String step,
                                String message) {
                        return new TrainingStatus(
                                        AlsTrainingStatusDTO.State.RUNNING,
                                        trainingVersion,
                                        step,
                                        startedAt,
                                        null,
                                        message);
                }

                private static TrainingStatus succeeded(
                                String trainingVersion,
                                LocalDateTime startedAt,
                                LocalDateTime finishedAt,
                                String message) {
                        return new TrainingStatus(
                                        AlsTrainingStatusDTO.State.SUCCEEDED,
                                        trainingVersion,
                                        "COMPLETED",
                                        startedAt,
                                        finishedAt,
                                        message);
                }

                private static TrainingStatus failed(
                                String trainingVersion,
                                LocalDateTime startedAt,
                                LocalDateTime finishedAt,
                                String message) {
                        return new TrainingStatus(
                                        AlsTrainingStatusDTO.State.FAILED,
                                        trainingVersion,
                                        "FAILED",
                                        startedAt,
                                        finishedAt,
                                        message);
                }

                private AlsTrainingStatusDTO toDto(String modelName, String activeModelVersion) {
                        return new AlsTrainingStatusDTO(
                                        modelName,
                                        activeModelVersion,
                                        trainingVersion,
                                        state,
                                        step,
                                        startedAt,
                                        finishedAt,
                                        message);
                }
        }

}
