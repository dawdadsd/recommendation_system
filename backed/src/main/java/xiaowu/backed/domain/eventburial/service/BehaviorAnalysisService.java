package xiaowu.backed.domain.eventburial.service;

import xiaowu.backed.domain.eventburial.aggregate.UserBehaviorAggregate;
import xiaowu.backed.domain.eventburial.entity.BehaviorEvent;
import xiaowu.backed.domain.eventburial.valueobject.BehaviorType;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class BehaviorAnalysisService {
    /**
     * 机器人行为检查
     */
    public boolean detectAnomalousPattern(UserBehaviorAggregate userBehavior){
        List<BehaviorEvent> recentEvents = userBehavior.getRecentEvents(1);
        if(recentEvents.size() > 1000){
            return true;
        }
        Set<BehaviorType> uniqueTypes = recentEvents.stream()
                .map(BehaviorEvent::getBehaviorType)
                .collect(Collectors.toSet());
        if(recentEvents.size() > 50 && uniqueTypes.size() == 1 ){
            return true;
        }
    }
}
