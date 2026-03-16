package xiaowu.backed.infrastructure.spark;

import static org.apache.spark.sql.functions.*;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.springframework.stereotype.Component;

/**
 * Kafka 消息反序列化器，将Kafka二进制消息转换为结构化的 Spark DataFrame
 * 
 * @author xiaowu
 */
@Component
public class KafkaEventDeserializer {
  private static final StructType EVENT_SCHEMA = new StructType()
      .add("eventId", DataTypes.StringType, false)
      .add("userId", DataTypes.LongType, false)
      .add("itemId", DataTypes.LongType, false)
      .add("behaviorType", DataTypes.StringType, false)
      .add("rating", DataTypes.DoubleType, true)
      .add("sessionId", DataTypes.StringType, true)
      .add("deviceInfo", DataTypes.StringType, true)
      .add("timestamp", DataTypes.StringType, false);

  public Dataset<Row> deserialize(Dataset<Row> rawKafkaStream) {
    return rawKafkaStream
        .selectExpr("CAST(value AS STRING) AS json_str")
        .select(from_json(col("json_str"), EVENT_SCHEMA).as("e"))
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
  }
}
