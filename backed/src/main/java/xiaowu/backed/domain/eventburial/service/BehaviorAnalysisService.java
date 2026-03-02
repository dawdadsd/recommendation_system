package xiaowu.backed.domain.eventburial.service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import xiaowu.backed.domain.eventburial.aggregate.UserBehaviorAggregate;
import xiaowu.backed.domain.eventburial.entity.BehaviorEvent;
import xiaowu.backed.domain.eventburial.valueobject.BehaviorType;

/**
 * @author xiaowu
 */
public class BehaviorAnalysisService {

    /** 检测异常行为（机器人刷量）：1小时内超1000次，或50次以上且行为类型单一 */
    public boolean detectAnomalousPattern(UserBehaviorAggregate userBehavior) {
        List<BehaviorEvent> recentEvents = userBehavior.getRecentEvents(1);
        if (recentEvents.size() > 1000) {
            return true;
        }
        Set<BehaviorType> uniqueTypes = recentEvents.stream()
                .map(BehaviorEvent::getBehaviorType)
                .collect(Collectors.toSet());
        if (recentEvents.size() > 50 && uniqueTypes.size() == 1) {
            return true;
        }
        return false;
    }
}
