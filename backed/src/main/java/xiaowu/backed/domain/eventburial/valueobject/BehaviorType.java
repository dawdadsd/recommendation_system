package xiaowu.backed.domain.eventburial.valueobject;

import lombok.Getter;
import lombok.Setter;

@Getter
public enum BehaviorType {
    VIEW("浏览",1),
    CLICK("点击",1),
    ADD_TO_CART("加入购物车",3),
    PURCHASE("购买",5),
    RATE("评分",4);
    private final String description;
    private final int weight;
    BehaviorType(String description,int weight){
        this.description = description;
        this.weight = weight;
    }

    /**
     * 从字符串安全转换为枚举类型
     * 防止无效输入导致的异常
     */
    public static BehaviorType forString(String action){
        if(action == null || action.trim().isEmpty()){
            throw new IllegalArgumentException("行为类型不能为空");
        }
        try{
            return BehaviorType.valueOf(action.toUpperCase());
        }catch (IllegalArgumentException e){
            throw new IllegalArgumentException("不支持的行为类型 : " + action);
        }
    }
}
