package xiaowu.trainer.infrastructure.persistence.recommendation;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.current_timestamp;
import static org.apache.spark.sql.functions.lit;

import org.apache.spark.storage.StorageLevel;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import xiaowu.trainer.domain.recommendation.repository.UserCfRecallWriteRepository;

@Repository
public class JdbcUserCfRecallWriteRepository implements UserCfRecallWriteRepository {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Override
    public long replaceModelRecall(String modelVersion, Dataset<Row> recommendationDf) {
        var exploded = recommendationDf
                .selectExpr("userId", "posexplode(recommendations) as (pos, recommendation)");

        var flattened = exploded
                .select(
                        lit(modelVersion).as("model_version"),
                        col("userId").cast("long").as("user_id"),
                        col("recommendation.itemId").cast("long").as("item_id"),
                        col("pos").plus(lit(1)).cast("int").as("rank_position"),
                        col("recommendation.rating").cast("double").as("score"),
                        lit("ALS").as("source"),
                        current_timestamp().as("created_at"),
                        current_timestamp().as("updated_at"))
                .persist(StorageLevel.MEMORY_AND_DISK());

        try {
            long rowCount = flattened.count();

            flattened.write()
                    .format("jdbc")
                    .option("url", jdbcUrl)
                    .option("dbtable", "user_cf_recall")
                    .option("user", dbUsername)
                    .option("password", dbPassword)
                    .option("driver", "com.mysql.cj.jdbc.Driver")
                    .mode("append")
                    .save();

            return rowCount;
        } finally {
            flattened.unpersist();
        }
    }
}
