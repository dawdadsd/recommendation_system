package xiaowu.backed.application.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.backed.domain.eventburial.repository.UserBehaviorAggregationRepository;
import xiaowu.backed.domain.user.entity.UserProfile;
import xiaowu.backed.domain.user.repository.UserProfileRepository;

/**
 * 用户画像服务 —— AI 对话和推荐系统的核心前置依赖
 *
 * <p>
 * 职责：将分散在多张表中的用户数据（静态属性 + 动态行为）
 * 整合为一份结构化的画像文本，供 AI System Prompt 使用。
 *
 * <p>
 * 为什么不直接在 Controller 里拼字符串？
 * 画像拼装涉及多表查询和业务规则（行为模式判定、偏好排序），
 * 属于业务逻辑，必须放在 Service 层。Controller 只负责 HTTP 映射。
 *
 * <p>
 * 为什么用 @RequiredArgsConstructor + final 而非 @Autowired？
 * 构造器注入保证依赖不可变、编译期检查、可测试性。
 * Lombok 自动生成构造器，消除样板代码。
 *
 * @author xiaowu
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileService {

    private final UserProfileRepository userProfileRepository;
    private final UserBehaviorAggregationRepository behaviorRepository;

    /**
     * 为 AI 对话拼装完整的用户画像 System Prompt
     *
     * <p>
     * 为什么返回 String 而非 DTO？
     * 这个方法的唯一消费者是 AI 的 System Prompt 拼装。
     * 返回结构化文本比返回 DTO 再让调用方拼装更内聚 ——
     * "画像长什么样"的知识封装在此方法内，调用方无需关心。
     *
     * <p>
     * 为什么分两步查询而非 JOIN？
     * user_profile 和 user_behavior_aggregation 是不同聚合根，
     * 没有 JPA 关联关系。分开查更清晰，且两张表都按 userId 走索引，
     * 性能与 JOIN 无差别。
     *
     * @param userId 用户 ID
     * @return 格式化的画像文本，可直接注入 System Prompt；用户不存在时返回默认画像
     */
    @Transactional(readOnly = true)
    public String buildUserPortrait(Long userId) {
        log.info("[Portrait] ========== 开始构建用户画像 userId={} ==========", userId);

        // Step 1: 查询静态画像
        var profile = userProfileRepository.findByUserId(userId).orElse(null);
        if (profile != null) {
            log.info("[Portrait] 静态画像命中: nickname={}, gender={}, ageRange={}, region={}, tags={}",
                    profile.getNickname(), profile.getGender(), profile.getAgeRange(),
                    profile.getRegion(), profile.getPreferenceTags());
        } else {
            log.info("[Portrait] 静态画像未命中: userId={} 为新访客，使用默认画像", userId);
        }

        // Step 2: 查询行为数据
        var behaviorSummary = queryBehaviorSummary(userId);
        if (!behaviorSummary.isEmpty()) {
            log.info("[Portrait] 行为数据命中: userId={}, 近7天行为分布={}",
                    userId, behaviorSummary);
        } else {
            log.info("[Portrait] 行为数据为空: userId={} 近7天无行为记录", userId);
        }

        // Step 3: 推断行为模式
        var pattern = inferBehaviorPattern(behaviorSummary);
        log.info("[Portrait] 行为模式推断: userId={}, pattern={}", userId, pattern);

        // Step 4: 拼装画像文本
        var sb = new StringBuilder();
        sb.append("你正在和一位用户对话。以下是该用户的画像：\n");
        appendStaticProfile(sb, profile);
        appendBehaviorProfile(sb, behaviorSummary);
        appendGuidelines(sb, profile);

        var portrait = sb.toString();
        log.info("[Portrait] ========== 画像构建完成 userId={} ==========\n{}", userId, portrait);
        return portrait;
    }

    /**
     * 查询用户是否存在
     */
    @Transactional(readOnly = true)
    public boolean existsByUserId(Long userId) {
        return userProfileRepository.existsByUserId(userId);
    }

    /**
     * 获取用户静态画像
     */
    @Transactional(readOnly = true)
    public UserProfile getProfile(Long userId) {
        return userProfileRepository.findByUserId(userId).orElse(null);
    }

    // ========================= 内部方法 =========================

    /**
     * 查询用户近 7 天的行为分布
     *
     * <p>
     * 为什么选 7 天？
     * 7 天是短期兴趣窗口：足够捕捉近期偏好变化，
     * 又不会被一个月前的偶发行为污染。
     * 未来可提取为配置项。
     */
    private List<BehaviorStat> queryBehaviorSummary(Long userId) {
        var since = LocalDateTime.now().minusDays(7);
        var raw = behaviorRepository.findBehaviorSummaryByUserId(userId, since);

        return raw.stream()
                .map(row -> new BehaviorStat(
                        (String) row[0],
                        ((Number) row[1]).longValue()))
                .toList();
    }

    /**
     * 拼装静态画像部分
     *
     * <p>
     * 为什么用默认值而非跳过？
     * AI 需要完整的上下文才能给出高质量回答。
     * "性别未知"比"没有性别字段"对 AI 更有信息量 ——
     * 前者告诉 AI "不要做性别假设"，后者让 AI 猜测。
     */
    private void appendStaticProfile(StringBuilder sb, UserProfile profile) {
        if (profile == null) {
            sb.append("- 用户基础信息：未注册，新访客\n");
            return;
        }

        sb.append("- 昵称：").append(valueOrDefault(profile.getNickname(), "未设置")).append('\n');
        sb.append("- 性别：").append(mapGender(profile.getGender())).append('\n');
        sb.append("- 年龄段：").append(valueOrDefault(profile.getAgeRange(), "未知")).append('\n');
        sb.append("- 地域：").append(valueOrDefault(profile.getRegion(), "未知")).append('\n');

        if (profile.getPreferenceTags() != null && !profile.getPreferenceTags().isBlank()) {
            sb.append("- 自选偏好：").append(profile.getPreferenceTags()).append('\n');
        }
    }

    /**
     * 拼装行为画像部分
     *
     * <p>
     * 为什么要判定行为模式（比价型/目标明确型）？
     * 相同的推荐请求，不同行为模式的用户期望完全不同：
     * - 比价型用户（浏览多购买少）→ AI 应强调性价比、对比
     * - 目标明确型用户（浏览少购买多）→ AI 应直接推荐 Top 选项
     * 这个标签让 AI 的回答风格自适应。
     */
    private void appendBehaviorProfile(StringBuilder sb, List<BehaviorStat> stats) {
        if (stats.isEmpty()) {
            sb.append("- 行为记录：暂无（新用户）\n");
            return;
        }

        sb.append("- 近 7 天行为分布：\n");
        for (var stat : stats) {
            sb.append("  - ").append(mapBehaviorType(stat.type()))
                    .append("：").append(stat.count()).append(" 次\n");
        }

        sb.append("- 行为模式：").append(inferBehaviorPattern(stats)).append('\n');
    }

    /**
     * 拼装 AI 对话指导原则
     *
     * <p>
     * 为什么把指导原则也放在画像里？
     * System Prompt 的最佳实践是"上下文 + 指令"放在一起。
     * 画像和指令分开传会导致 AI 不知道该怎么用画像信息。
     * 放在一起，AI 能直接根据画像调整对话策略。
     */
    private void appendGuidelines(StringBuilder sb, UserProfile profile) {
        sb.append('\n');
        sb.append("请基于以上画像，以专业但亲和的语气与用户对话。\n");

        if (profile != null && profile.getPreferenceTags() != null) {
            sb.append("当用户询问推荐时，优先推荐与偏好标签（")
                    .append(profile.getPreferenceTags())
                    .append("）相关的产品。\n");
        }

        sb.append("如果用户是新访客，主动询问偏好以建立画像。\n");
    }

    /**
     * 推断行为模式
     *
     * <p>
     * 算法：计算 VIEW 占比。
     * VIEW 占总行为 > 70% → 比价型（浏览多，犹豫不决）
     * PURCHASE 占比 > 30% → 目标明确型（下手快）
     * 其余 → 探索型（多种行为均匀分布）
     */
    private String inferBehaviorPattern(List<BehaviorStat> stats) {
        long total = stats.stream().mapToLong(BehaviorStat::count).sum();
        if (total == 0) {
            return "未知";
        }

        long viewCount = stats.stream()
                .filter(s -> "VIEW".equals(s.type()) || "CLICK".equals(s.type()))
                .mapToLong(BehaviorStat::count)
                .sum();

        long purchaseCount = stats.stream()
                .filter(s -> "PURCHASE".equals(s.type()))
                .mapToLong(BehaviorStat::count)
                .sum();

        double viewRatio = (double) viewCount / total;
        double purchaseRatio = (double) purchaseCount / total;

        if (viewRatio > 0.7) {
            return "比价型（浏览多购买少，建议强调性价比和对比）";
        } else if (purchaseRatio > 0.3) {
            return "目标明确型（决策快，建议直接推荐 Top 选项）";
        } else {
            return "探索型（行为均匀，建议多样化推荐）";
        }
    }

    // ========================= 工具方法 =========================

    private String mapGender(String gender) {
        return switch (gender) {
            case "MALE" -> "男";
            case "FEMALE" -> "女";
            default -> "未知";
        };
    }

    private String mapBehaviorType(String type) {
        return switch (type) {
            case "VIEW" -> "浏览";
            case "CLICK" -> "点击";
            case "ADD_TO_CART" -> "加购";
            case "PURCHASE" -> "购买";
            case "RATE" -> "评分";
            default -> type;
        };
    }

    private String valueOrDefault(String value, String defaultValue) {
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    /**
     * 行为统计内部记录
     *
     * <p>
     * 为什么用 record 而非 Map.Entry？
     * record 是 Java 17 的不可变数据载体，类型安全且自文档化。
     * Map.Entry<String, Long> 语义模糊，看不出 key 是行为类型、value 是次数。
     */
    private record BehaviorStat(String type, long count) {
    }
}
