package xiaowu.backed.domain.dataprocessing.aggregate;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import xiaowu.backed.domain.dataprocessing.entity.UserInteraction;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 交互矩阵聚合根
 * 管理用户-物品交互数据，用于协同过滤算法
 * 
 * @author xiaowu
 * @version 1.0.0
 * @since 2025-07-14
 */
@Getter
public class InteractionMatrix {
    private final Map<Long, Map<Long, Double>> userItemMatrix;     // 用户-物品评分矩阵
    private final Map<Long, Set<Long>> userItems;                  // 用户交互过的物品
    private final Map<Long, Set<Long>> itemUsers;                  // 物品被哪些用户交互过
    private final Map<Long, Double> userAverageRatings;            // 用户平均评分
    private final Map<Long, Double> itemAverageRatings;            // 物品平均评分
    private final Set<Long> allUsers;                              // 所有用户ID
    private final Set<Long> allItems;                              // 所有物品ID
    private final Instant lastUpdated;                             // 最后更新时间
    private final int totalInteractions;                           // 总交互数量
    
    private InteractionMatrix(Builder builder) {
        this.userItemMatrix = Collections.unmodifiableMap(deepCopyUserItemMatrix(builder.userItemMatrix));
        this.userItems = Collections.unmodifiableMap(deepCopyUserItems(builder.userItems));
        this.itemUsers = Collections.unmodifiableMap(deepCopyItemUsers(builder.itemUsers));
        this.userAverageRatings = Map.copyOf(builder.userAverageRatings);
        this.itemAverageRatings = Map.copyOf(builder.itemAverageRatings);
        this.allUsers = Set.copyOf(builder.allUsers);
        this.allItems = Set.copyOf(builder.allItems);
        this.lastUpdated = builder.lastUpdated;
        this.totalInteractions = builder.totalInteractions;
    }
    
    /**
     * 创建空的交互矩阵
     */
    public static InteractionMatrix empty() {
        return new Builder().build();
    }
    
    /**
     * 从用户交互列表创建交互矩阵
     */
    public static InteractionMatrix fromInteractions(List<UserInteraction> interactions) {
        Builder builder = new Builder();
        
        for (UserInteraction interaction : interactions) {
            if (interaction.isValid()) {
                builder.addInteraction(interaction);
            }
        }
        
        return builder.build();
    }
    
    /**
     * 获取用户对物品的评分
     */
    public double getRating(Long userId, Long itemId) {
        return userItemMatrix.getOrDefault(userId, Collections.emptyMap())
                .getOrDefault(itemId, 0.0);
    }
    
    /**
     * 检查用户是否与物品有交互
     */
    public boolean hasInteraction(Long userId, Long itemId) {
        return userItems.getOrDefault(userId, Collections.emptySet()).contains(itemId);
    }
    
    /**
     * 获取用户交互过的所有物品
     */
    public Set<Long> getUserItems(Long userId) {
        return userItems.getOrDefault(userId, Collections.emptySet());
    }
    
    /**
     * 获取与物品交互过的所有用户
     */
    public Set<Long> getItemUsers(Long itemId) {
        return itemUsers.getOrDefault(itemId, Collections.emptySet());
    }
    
    /**
     * 获取用户的平均评分
     */
    public double getUserAverageRating(Long userId) {
        return userAverageRatings.getOrDefault(userId, 0.0);
    }
    
    /**
     * 获取物品的平均评分
     */
    public double getItemAverageRating(Long itemId) {
        return itemAverageRatings.getOrDefault(itemId, 0.0);
    }
    
    /**
     * 计算用户相似度（皮尔逊相关系数）
     */
    public double calculateUserSimilarity(Long userId1, Long userId2) {
        Set<Long> commonItems = new HashSet<>(getUserItems(userId1));
        commonItems.retainAll(getUserItems(userId2));
        
        if (commonItems.size() < 2) {
            return 0.0; // 共同物品太少，无法计算相似度
        }
        
        double user1Avg = getUserAverageRating(userId1);
        double user2Avg = getUserAverageRating(userId2);
        
        double numerator = 0.0;
        double denominator1 = 0.0;
        double denominator2 = 0.0;
        
        for (Long itemId : commonItems) {
            double rating1 = getRating(userId1, itemId) - user1Avg;
            double rating2 = getRating(userId2, itemId) - user2Avg;
            
            numerator += rating1 * rating2;
            denominator1 += rating1 * rating1;
            denominator2 += rating2 * rating2;
        }
        
        if (denominator1 == 0.0 || denominator2 == 0.0) {
            return 0.0;
        }
        
        return numerator / Math.sqrt(denominator1 * denominator2);
    }
    
    /**
     * 计算物品相似度（余弦相似度）
     */
    public double calculateItemSimilarity(Long itemId1, Long itemId2) {
        Set<Long> commonUsers = new HashSet<>(getItemUsers(itemId1));
        commonUsers.retainAll(getItemUsers(itemId2));
        
        if (commonUsers.size() < 2) {
            return 0.0;
        }
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (Long userId : commonUsers) {
            double rating1 = getRating(userId, itemId1);
            double rating2 = getRating(userId, itemId2);
            
            dotProduct += rating1 * rating2;
            norm1 += rating1 * rating1;
            norm2 += rating2 * rating2;
        }
        
        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        
        return dotProduct / Math.sqrt(norm1 * norm2);
    }
    
    /**
     * 获取用户最相似的N个用户
     */
    public List<SimilarityPair<Long>> getMostSimilarUsers(Long userId, int count) {
        return allUsers.stream()
                .filter(otherUserId -> !otherUserId.equals(userId))
                .map(otherUserId -> new SimilarityPair<>(otherUserId, calculateUserSimilarity(userId, otherUserId)))
                .filter(pair -> pair.similarity > 0.1) // 过滤相似度过低的用户
                .sorted((a, b) -> Double.compare(b.similarity, a.similarity))
                .limit(count)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取物品最相似的N个物品
     */
    public List<SimilarityPair<Long>> getMostSimilarItems(Long itemId, int count) {
        return allItems.stream()
                .filter(otherItemId -> !otherItemId.equals(itemId))
                .map(otherItemId -> new SimilarityPair<>(otherItemId, calculateItemSimilarity(itemId, otherItemId)))
                .filter(pair -> pair.similarity > 0.1)
                .sorted((a, b) -> Double.compare(b.similarity, a.similarity))
                .limit(count)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取矩阵的稀疏度
     */
    public double getSparsity() {
        long totalPossibleInteractions = (long) allUsers.size() * allItems.size();
        return totalPossibleInteractions == 0 ? 0.0 : 
               1.0 - (double) totalInteractions / totalPossibleInteractions;
    }

    /**
     * 相似度对内部类
     */
    @Getter
    public static class SimilarityPair<T> {
        private final T id;
        private final double similarity;

        public SimilarityPair(T id, double similarity) {
            this.id = id;
            this.similarity = similarity;
        }
    }
    
    /**
     * 深拷贝用户-物品矩阵
     */
    private Map<Long, Map<Long, Double>> deepCopyUserItemMatrix(Map<Long, Map<Long, Double>> original) {
        Map<Long, Map<Long, Double>> copy = new HashMap<>();
        for (Map.Entry<Long, Map<Long, Double>> entry : original.entrySet()) {
            copy.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return copy;
    }
    
    /**
     * 深拷贝用户物品映射
     */
    private Map<Long, Set<Long>> deepCopyUserItems(Map<Long, Set<Long>> original) {
        return getLongSetMap(original);
    }
    
    /**
     * 深拷贝物品用户映射
     */
    private Map<Long, Set<Long>> deepCopyItemUsers(Map<Long, Set<Long>> original) {
        return getLongSetMap(original);
    }

    @NotNull
    private Map<Long, Set<Long>> getLongSetMap(Map<Long, Set<Long>> original) {
        Map<Long, Set<Long>> copy = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : original.entrySet()) {
            copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return copy;
    }

    /**
     * 交互矩阵建造者
     */
    public static class Builder {
        private final Map<Long, Map<Long, Double>> userItemMatrix = new HashMap<>();
        private final Map<Long, Set<Long>> userItems = new HashMap<>();
        private final Map<Long, Set<Long>> itemUsers = new HashMap<>();
        private final Map<Long, Double> userAverageRatings = new HashMap<>();
        private final Map<Long, Double> itemAverageRatings = new HashMap<>();
        private final Set<Long> allUsers = new HashSet<>();
        private final Set<Long> allItems = new HashSet<>();
        private Instant lastUpdated = Instant.now();
        private int totalInteractions = 0;
        
        /**
         * 添加用户交互
         */
        public Builder addInteraction(UserInteraction interaction) {
            Long userId = interaction.getUserId();
            Long itemId = interaction.getItemId();
            double rating = interaction.getNormalizedRating();
            
            // 更新用户-物品矩阵
            userItemMatrix.computeIfAbsent(userId, k -> new HashMap<>()).put(itemId, rating);
            
            // 更新用户物品映射
            userItems.computeIfAbsent(userId, k -> new HashSet<>()).add(itemId);
            
            // 更新物品用户映射
            itemUsers.computeIfAbsent(itemId, k -> new HashSet<>()).add(userId);
            
            // 更新用户和物品集合
            allUsers.add(userId);
            allItems.add(itemId);
            
            totalInteractions++;
            lastUpdated = Instant.now();

            return this;
        }
        
        /**
         * 批量添加交互
         */
        public Builder addInteractions(List<UserInteraction> interactions) {
            interactions.forEach(this::addInteraction);
            return this;
        }
        
        /**
         * 构建交互矩阵
         */
        public InteractionMatrix build() {
            // 计算用户平均评分
            calculateUserAverageRatings();
            // 计算物品平均评分
            calculateItemAverageRatings();
            return new InteractionMatrix(this);
        }
        /**
         * 计算用户平均评分
         */
        private void calculateUserAverageRatings() {
            for (Map.Entry<Long, Map<Long, Double>> userEntry : userItemMatrix.entrySet()) {
                Long userId = userEntry.getKey();
                Map<Long, Double> items = userEntry.getValue();
                double average = items.values().stream()
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElse(0.0);
                
                userAverageRatings.put(userId, average);
            }
        }
        /**
         * 计算物品平均评分
         */
        private void calculateItemAverageRatings() {
            for (Long itemId : allItems) {
                double sum = 0.0;
                int count = 0;
                
                for (Map.Entry<Long, Map<Long, Double>> userEntry : userItemMatrix.entrySet()) {
                    Double rating = userEntry.getValue().get(itemId);
                    if (rating != null) {
                        sum += rating;
                        count++;
                    }
                }
                double average = count > 0 ? sum / count : 0.0;
                itemAverageRatings.put(itemId, average);
            }
        }
    }
}