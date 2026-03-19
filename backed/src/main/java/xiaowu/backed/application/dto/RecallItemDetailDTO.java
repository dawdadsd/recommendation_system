package xiaowu.backed.application.dto;

import java.math.BigDecimal;

public record RecallItemDetailDTO(
    Long itemId,
    Integer rankPosition,
    Double score,
    String name,
    String category,
    BigDecimal price,
    String tags) {
}
