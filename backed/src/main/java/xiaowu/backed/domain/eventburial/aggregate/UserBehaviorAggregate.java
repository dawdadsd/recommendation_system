package xiaowu.backed.domain.eventburial.aggregate;

import lombok.Getter;
import xiaowu.backed.domain.eventburial.entity.BehaviorEvent;
import xiaowu.backed.domain.eventburial.valueobject.BehaviorType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Getter
public class UserBehaviorAggregate {
    private final Long userId;
    private final List<BehaviorEvent> behaviorEvents;
    private final Instant lastUpdated;
    private UserBehaviorAggregate(Long userId,List<BehaviorEvent> behaviorEvent){
        this.userId = Objects.requireNonNull(userId,"用户ID不能为空");
        this.behaviorEvents = new ArrayList<>();
        this.lastUpdated = Instant.now();
    }
    public static UserBehaviorAggregate create(Long userId){
        return new UserBehaviorAggregate(userId,new ArrayList<BehaviorEvent>());
    }
    public static UserBehaviorAggregate rebuild(Long userId,ArrayList<BehaviorEvent> events){
        return new UserBehaviorAggregate(userId,events);
    }
    public void addBehaviorEvent(BehaviorEvent event){
        Objects.requireNonNull(event,"行为事件不能为空");
        if(!event.getUserId().equals(userId)){
            throw new IllegalArgumentException("事件Id与用户聚合用户Id不匹配");
        }
        if (!event.isValid()) {
            throw new IllegalArgumentException("无效的行为事件");
        }
        // 检查重复事件（同一用户对同一商品在短时间内的相同行为）
        if (isDuplicateEvent(event)) {
            return; // 忽略重复事件
        }
        behaviorEvents.add(event);
        // 应用领域规则：限制事件数量以避免内存问题
        if (behaviorEvents.size() > 10000) {
            removeOldestEvents();
        }
    }
    private boolean isDuplicateEvent(BehaviorEvent newEvent){
        Instant fiveMinutesAgo = newEvent.getTimestamp().minus(5, ChronoUnit.MINUTES);
        return behaviorEvents.stream()
                .filter(event -> event.getItemId().equals(newEvent.getItemId()))
                .filter(event -> event.getBehaviorType().equals(newEvent.getBehaviorType()))
                .anyMatch(event -> event.getTimestamp().isAfter(fiveMinutesAgo));
    }
    private void removeOldestEvents(){
        behaviorEvents.sort(Comparator.comparing(BehaviorEvent::getTimestamp));
        for(int i = 0; i < 1000 && !behaviorEvents.isEmpty() ; i++)
        {
            behaviorEvents.remove(0);
        }
    }
    public List<BehaviorEvent> getEventsInTimeRange(Instant startTime,Instant endTime){
        return behaviorEvents.stream()
                .filter(event -> !event.getTimestamp().isBefore(startTime))
                .filter(event -> !event.getTimestamp().isAfter(endTime))
                .collect(Collectors.toList());
    }
    public List<BehaviorEvent> getRecentEvents(int hours){
        Instant cutoffTime = Instant.now().minus(hours, ChronoUnit.HOURS);
        return getEventsInTimeRange(cutoffTime,Instant.now());
    }

    /**
     * 计算用户对特定商品类别的偏好度
     * 这是推荐算法的基础特征
     */
    public Map<String, Double> calculateCategoryPreferences() {
        Map<String, Double> preferences = new HashMap<>();
        // 按商品类别分组计算权重和
        // 这里假设从itemId可以获取类别信息，实际实现需要商品服务
        for (BehaviorEvent event : behaviorEvents) {
            String category = "category_" + (event.getItemId() % 10); // 模拟分类
            double weight = event.calculateEventWeight();
            preferences.merge(category, weight, Double::sum);
        }
        // 标准化偏好度
        double totalWeight = preferences.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight > 0) {
            preferences.replaceAll((k, v) -> v / totalWeight);
        }
        return preferences;
    }
    /**
     * 检查用户是否为活跃用户
     * 用于推荐策略的选择
     */
    public boolean isActiveUser() {
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        long recentEventCount = behaviorEvents.stream()
                .filter(event -> event.getTimestamp().isAfter(sevenDaysAgo))
                .count();
        return recentEventCount >= 10; // 7天内至少10次行为
    }
    /**
     * 获取用户最喜欢的行为类型
     */
    public BehaviorType getMostFrequentBehaviorType() {
        return behaviorEvents.stream()
                .collect(Collectors.groupingBy(BehaviorEvent::getBehaviorType, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(BehaviorType.VIEW);
    }
}
