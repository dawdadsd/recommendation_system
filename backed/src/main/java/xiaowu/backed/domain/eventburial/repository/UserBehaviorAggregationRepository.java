package xiaowu.backed.domain.eventburial.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import xiaowu.backed.domain.eventburial.entity.UserBehaviorAggregation;

/**
 * 用户行为聚合数据访问
 *
 * <p>画像服务查询用户近期行为统计，用于动态画像拼装。
 * 核心查询：按 userId 获取最近 N 个窗口的行为分布。
 *
 * @author xiaowu
 */
public interface UserBehaviorAggregationRepository extends JpaRepository<UserBehaviorAggregation, Long> {

    /**
     * 查询用户最近 N 个窗口的行为聚合（按窗口时间倒序）
     */
    List<UserBehaviorAggregation> findByUserIdOrderByWindowStartDesc(Long userId);

    /**
     * 查询用户指定时间范围内的行为聚合
     */
    List<UserBehaviorAggregation> findByUserIdAndWindowStartAfter(Long userId, LocalDateTime after);

    /**
     * 查询用户各行为类型的总事件数（用于计算行为模式标签）
     *
     * @return 每行：[behaviorType, totalCount]
     */
    @Query("""
            SELECT a.behaviorType, SUM(a.eventCount)
            FROM UserBehaviorAggregation a
            WHERE a.userId = :userId AND a.windowStart >= :since
            GROUP BY a.behaviorType
            ORDER BY SUM(a.eventCount) DESC
            """)
    List<Object[]> findBehaviorSummaryByUserId(
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since);
}
