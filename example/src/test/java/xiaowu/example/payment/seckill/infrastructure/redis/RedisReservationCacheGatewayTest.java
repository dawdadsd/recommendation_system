package xiaowu.example.payment.seckill.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import xiaowu.example.payment.seckill.application.port.ReservationCacheGateway.ReleaseCacheCommand;
import xiaowu.example.payment.seckill.application.port.ReservationCacheGateway.ReserveCacheCommand;
import xiaowu.example.payment.seckill.application.port.ReservationCacheGateway.ReserveStatus;

class RedisReservationCacheGatewayTest {

  private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final RedisScript<List> reserveScript = mock(RedisScript.class);

  @SuppressWarnings("unchecked")
  private final RedisScript<List> releaseScript = mock(RedisScript.class);

  private RedisReservationCacheGateway gateway;

  @BeforeEach
  void setUp() {
    gateway = new RedisReservationCacheGateway(redisTemplate, reserveScript, releaseScript);
  }

  @Test
  void tryReserveShouldBuildExpectedKeysAndMapReservedStatus() {
    LocalDateTime expireAt = LocalDateTime.of(2026, 3, 25, 20, 15, 30);
    when(redisTemplate.execute(eq(reserveScript), anyList(), any(), any(), any(), any(), any()))
        .thenReturn(List.of(1L, "RESERVED"));

    var result = gateway.tryReserve(new ReserveCacheCommand(
        "RSV_1001",
        2001L,
        3001L,
        4001L,
        expireAt));

    assertThat(result.status()).isEqualTo(ReserveStatus.RESERVED);
    assertThat(result.message()).isEqualTo("RESERVED");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> reservationIdCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> activityIdCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> skuIdCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> expireAtCaptor = ArgumentCaptor.forClass(String.class);

    verify(redisTemplate).execute(
        eq(reserveScript),
        keysCaptor.capture(),
        userIdCaptor.capture(),
        reservationIdCaptor.capture(),
        activityIdCaptor.capture(),
        skuIdCaptor.capture(),
        expireAtCaptor.capture());

    assertThat(keysCaptor.getValue()).containsExactly(
        "seckill:stock:3001:available",
        "seckill:users:2001:3001",
        "seckill:reservation:RSV_1001",
        RedisReservationCacheGateway.RELEASE_ZSET_KEY);
    assertThat(userIdCaptor.getValue()).isEqualTo("4001");
    assertThat(reservationIdCaptor.getValue()).isEqualTo("RSV_1001");
    assertThat(activityIdCaptor.getValue()).isEqualTo("2001");
    assertThat(skuIdCaptor.getValue()).isEqualTo("3001");
    assertThat(expireAtCaptor.getValue())
        .isEqualTo(String.valueOf(expireAt.toEpochSecond(ZoneOffset.ofHours(8))));
  }

  @Test
  void tryReserveShouldMapFailureStatusesFromScriptResult() {
    when(redisTemplate.execute(eq(reserveScript), anyList(), any(), any(), any(), any(), any()))
        .thenReturn(List.of("0", "SOLD_OUT".getBytes(StandardCharsets.UTF_8)));

    var result = gateway.tryReserve(new ReserveCacheCommand(
        "RSV_1002",
        2002L,
        3002L,
        4002L,
        LocalDateTime.of(2026, 3, 25, 20, 15, 30)));

    assertThat(result.status()).isEqualTo(ReserveStatus.SOLD_OUT);
    assertThat(result.message()).isEqualTo("SOLD_OUT");
  }

  @Test
  void releaseShouldBuildExpectedKeysAndReturnTrueWhenScriptSucceeds() {
    when(redisTemplate.execute(eq(releaseScript), anyList(), any(), any()))
        .thenReturn(List.of(1, "RELEASED"));

    boolean released = gateway.release(new ReleaseCacheCommand(
        "RSV_1003",
        2003L,
        3003L,
        4003L,
        "TIMEOUT"));

    assertThat(released).isTrue();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> reservationIdCaptor = ArgumentCaptor.forClass(String.class);

    verify(redisTemplate).execute(
        eq(releaseScript),
        keysCaptor.capture(),
        userIdCaptor.capture(),
        reservationIdCaptor.capture());

    assertThat(keysCaptor.getValue()).containsExactly(
        "seckill:stock:3003:available",
        "seckill:users:2003:3003",
        "seckill:reservation:RSV_1003",
        RedisReservationCacheGateway.RELEASE_ZSET_KEY);
    assertThat(userIdCaptor.getValue()).isEqualTo("4003");
    assertThat(reservationIdCaptor.getValue()).isEqualTo("RSV_1003");
  }

  @Test
  void tryReserveShouldFailFastOnUnexpectedScriptResult() {
    when(redisTemplate.execute(eq(reserveScript), anyList(), any(), any(), any(), any(), any()))
        .thenReturn(null);

    assertThatThrownBy(() -> gateway.tryReserve(new ReserveCacheCommand(
        "RSV_1004",
        2004L,
        3004L,
        4004L,
        LocalDateTime.of(2026, 3, 25, 20, 15, 30))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("invalid reserve result");
  }
}
