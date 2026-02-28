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
        if (isDuplicateEvent(event)) {
            return;
        }
        behaviorEvents.add(event);
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

    /** 计算用户对各商品类别的偏好度（归一化权重），用于推荐算法特征 */
    public Map<String, Double> calculateCategoryPreferences() {
        Map<String, Double> preferences = new HashMap<>();
        for (BehaviorEvent event : behaviorEvents) {
            String category = "category_" + (event.getItemId() % 10);
            preferences.merge(category, event.calculateEventWeight(), Double::sum);
        }
        double totalWeight = preferences.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight > 0) {
            preferences.replaceAll((k, v) -> v / totalWeight);
        }
        return preferences;
    }

    /** 7天内行为数 >= 10 则视为活跃用户 */
    public boolean isActiveUser() {
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        long recentEventCount = behaviorEvents.stream()
                .filter(event -> event.getTimestamp().isAfter(sevenDaysAgo))
                .count();
        return recentEventCount >= 10;
    }

    public BehaviorType getMostFrequentBehaviorType() {
        return behaviorEvents.stream()
                .collect(Collectors.groupingBy(BehaviorEvent::getBehaviorType, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(BehaviorType.VIEW);
    }
}
