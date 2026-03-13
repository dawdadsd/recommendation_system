package xiaowu.backed.application.dto;

/**
 * Spark与Consumer的契约
 * 
 * @author xiaowu
 */
public record RecommendedItemDTO(
    Integer rank,
    Long itemId,
    Double score) {

}
