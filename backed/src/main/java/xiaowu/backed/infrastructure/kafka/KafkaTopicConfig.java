package xiaowu.backed.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 主题配置类：定义用户行为事件主题的分区、副本和保留策略
 * Topic的设计原则是按照数据语义划分，不是按照来源和目标划分的 —— 这也就意味着每个Topic的数据格式不同，消费策略不同以及吞吐量的差异
 * 区分Topic是新建还是复用的原则是：
 * 1.消费这个数据的是同一批消费者吗
 * 2.消费保留策略一样吗
 * 3.数据格式一样吗
 *
 * @author xiaowu
 */
@Configuration
@ConditionalOnProperty(name = "kafka.topic.auto-create", havingValue = "true")
public class KafkaTopicConfig {

        private final int defaultPartitions;
        private final short defaultReplicas;

        public KafkaTopicConfig(
                        @Value("${kafka.topic.default-partitions:1") int defaultPartitions,
                        @Value("${kafka.topic.default-replicas:1") short defaultReplicas) {
                this.defaultPartitions = defaultPartitions;
                this.defaultReplicas = defaultReplicas;
        }

        /**
         * @doc : partitions: 分区数 = 消费者并行度上限.12个分区意味着 12个消费者实例可以并行消费.通常按预期吞吐量 / 单分区吞吐量
         *      计算，再向上取整到方便扩展的数字
         *      replicas : 业界标准是 3
         *      副本，保证高可用性和数据安全，3个副本容忍1个broker故障，min.insync.replicas=2保证至少2个副本同步才能成功写入，防止数据丢失
         *      cleanup.policy: delete（默认）或
         *      compact，delete适合日志数据，compact适合状态数据。这里我们用delete，因为行为事件是时间序列数据，过期后可以删除
         *      retention.ms: 保留7天用于回放和分析: 推荐结果只要3天因为时效端，评分需要compact，所以设置为-1表示永久
         */
        @Bean
        public NewTopic userEventsTopic(@Value("${kafka.topic.user-events}") String topicName) {
                return TopicBuilder.name(topicName)
                                .partitions(defaultPartitions)
                                .replicas(defaultReplicas)
                                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000")
                                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                                .build();
        }

        @Bean
        public NewTopic productRatingsTopic(@Value("${kafka.topic.product-ratings}") String topicName) {
                return TopicBuilder.name(topicName)
                                .partitions(3)
                                .replicas(3)
                                .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
                                // 只保留每个Key最新值
                                .config(TopicConfig.CLEANUP_POLICY_COMPACT, "compact")
                                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
                                .build();
        }

        @Bean
        public NewTopic recommendationsTopic(@Value("${kafka.topic.recommendations}") String topicName) {
                return TopicBuilder.name(topicName)
                                .partitions(defaultPartitions)
                                .replicas(defaultReplicas)
                                .config(TopicConfig.RETENTION_MS_CONFIG, "259200000")
                                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                                .build();
        }

}
