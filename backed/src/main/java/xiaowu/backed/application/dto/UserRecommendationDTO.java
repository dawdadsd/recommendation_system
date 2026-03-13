package xiaowu.backed.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * Spark write For recommendations for json :{
 * "userId": 1, -> 后续也会是Redis的Key
 * "generatedAt": "2026-03-13T10:00:05Z",
 * "windowStart": "2026-03-13T10:00:00Z",
 * "windowEnd": "2026-03-13T10:00:30Z",
 * "items": [
 * { "rank": 1, "itemId": 101, "score": 8.0 },
 * { "rank": 2, "itemId": 88, "score": 5.0 }
 * ]
 * }`
 */
public record UserRecommendationDTO(
    Long userId,
    Instant generatedAt,
    Instant windowStart,
    Instant windowsEnd,
    List<RecommendedItemDTO> items) {

}
