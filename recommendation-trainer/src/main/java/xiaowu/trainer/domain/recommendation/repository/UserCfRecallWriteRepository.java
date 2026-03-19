package xiaowu.trainer.domain.recommendation.repository;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;

public interface UserCfRecallWriteRepository {

    long replaceModelRecall(String modelVersion, Dataset<Row> recommendationDf);
}
