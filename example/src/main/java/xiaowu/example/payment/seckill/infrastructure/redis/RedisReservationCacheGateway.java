package xiaowu.example.payment.seckill.infrastructure.redis;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import xiaowu.example.payment.seckill.application.port.ReservationCacheGateway;

public class RedisReservationCacheGateway implements ReservationCacheGateway {

  static final String RELEASE_ZSET_KEY = "seckill:release:zset";

  private static final ZoneOffset BUSINESS_ZONE_OFFSET = ZoneOffset.ofHours(8);

  private final StringRedisTemplate redisTemplate;
  private final RedisScript<List> reserveScript;
  private final RedisScript<List> releaseScript;

  public RedisReservationCacheGateway(
      StringRedisTemplate redisTemplate,
      RedisScript<List> reserveScript,
      RedisScript<List> releaseScript) {

    this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
    this.reserveScript = Objects.requireNonNull(reserveScript, "reserveScript");
    this.releaseScript = Objects.requireNonNull(releaseScript, "releaseScript");
  }

  @Override
  public ReserveCacheResult tryReserve(ReserveCacheCommand command) {
    Objects.requireNonNull(command, "command");

    List<String> keys = List.of(
        stockKey(command.skuId()),
        usersKey(command.activityId(), command.skuId()),
        reservationKey(command.reservationId()),
        RELEASE_ZSET_KEY);

    List<?> result = redisTemplate.execute(
        reserveScript,
        keys,
        command.userId().toString(),
        command.reservationId(),
        command.activityId().toString(),
        command.skuId().toString(),
        String.valueOf(command.expireAt().toEpochSecond(BUSINESS_ZONE_OFFSET)));

    ScriptResult scriptResult = ScriptResult.from(result, "reserve");
    if (scriptResult.code() == 1L) {
      return new ReserveCacheResult(ReserveStatus.RESERVED, scriptResult.message());
    }
    return new ReserveCacheResult(mapStatus(scriptResult.message()), scriptResult.message());
  }

  @Override
  public boolean release(ReleaseCacheCommand command) {
    Objects.requireNonNull(command, "command");

    List<String> keys = List.of(
        stockKey(command.skuId()),
        usersKey(command.activityId(), command.skuId()),
        reservationKey(command.reservationId()),
        RELEASE_ZSET_KEY);

    List<?> result = redisTemplate.execute(
        releaseScript,
        keys,
        command.userId().toString(),
        command.reservationId());

    return ScriptResult.from(result, "release").code() == 1L;
  }

  private static ReserveStatus mapStatus(String message) {
    return switch (message) {
      case "SOLD_OUT" -> ReserveStatus.SOLD_OUT;
      case "DUPLICATE_USER" -> ReserveStatus.DUPLICATE_USER;
      case "ACTIVITY_CLOSED" -> ReserveStatus.ACTIVITY_CLOSED;
      default -> ReserveStatus.ACTIVITY_CLOSED;
    };
  }

  private static String stockKey(Long skuId) {
    return "seckill:stock:" + skuId + ":available";
  }

  private static String usersKey(Long activityId, Long skuId) {
    return "seckill:users:" + activityId + ":" + skuId;
  }

  private static String reservationKey(String reservationId) {
    return "seckill:reservation:" + reservationId;
  }

  private record ScriptResult(long code, String message) {
    /**
     * 从Redis脚本执行结果中解析出状态码和消息
     *
     * @param rawResult Redis脚本执行结果，预期为一个包含两个元素的列表，第一个元素是状态码，第二个元素是消息
     * @param operation 操作类型，用于日志和异常信息中区分是预订还是释放操作
     * @return 解析后的ScriptResult对象，包含状态码和消息
     */
    private static ScriptResult from(List<?> rawResult, String operation) {
      if (rawResult == null || rawResult.size() < 2) {
        throw new IllegalStateException("Redis script returned invalid " + operation + " result: " + rawResult);
      }
      return new ScriptResult(asLong(rawResult.get(0), operation), asString(rawResult.get(1), operation));
    }

    private static long asLong(Object value, String operation) {
      if (value instanceof Number number) {
        return number.longValue();
      }
      if (value instanceof byte[] bytes) {
        return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
      }
      if (value instanceof String text) {
        return Long.parseLong(text);
      }
      throw new IllegalStateException(
          "Redis script returned non-numeric code for " + operation + ": " + value);
    }

    private static String asString(Object value, String operation) {
      if (value instanceof String text) {
        return text;
      }
      if (value instanceof byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
      }
      if (value != null) {
        return value.toString();
      }
      throw new IllegalStateException(
          "Redis script returned null message for " + operation);
    }
  }
}
