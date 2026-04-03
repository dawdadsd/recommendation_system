package xiaowu.example.payment.seckill.infrastructure.redis;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import lombok.extern.slf4j.Slf4j;
import xiaowu.example.payment.seckill.application.port.ReservationCacheGateway;
import xiaowu.example.payment.seckill.domain.repository.SeckillStockRepository;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class SeckillRedisConfiguration {

  @Bean("seckillReserveScript")
  RedisScript<List> seckillReserveScript() {
    return loadScript("lua/seckill_reserve.lua");
  }

  @Bean("seckillReleaseScript")
  RedisScript<List> seckillReleaseScript() {
    return loadScript("lua/seckill_release.lua");
  }

  @Bean
  @ConditionalOnMissingBean(ReservationCacheGateway.class)
  ReservationCacheGateway reservationCacheGateway(
      StringRedisTemplate redisTemplate,
      @Qualifier("seckillReserveScript") RedisScript<List> reserveScript,
      @Qualifier("seckillReleaseScript") RedisScript<List> releaseScript) {

    return new RedisReservationCacheGateway(redisTemplate, reserveScript, releaseScript);
  }

  private static RedisScript<List> loadScript(String location) {
    DefaultRedisScript<List> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource(location));
    script.setResultType(List.class);
    return script;
  }

  /**
   * 应用启动后将 seckill_stock 表中的可用库存预热到 Redis。
   *
   * <p>
   * 使用 {@code SET NX}（setIfAbsent）写入，避免覆盖已有的 Redis 键，
   * 确保重启后缓存未过期的条目不受影响。
   * Lua 脚本将缺失的库存键视为 {@code ACTIVITY_CLOSED}，因此预热是
   * 让 reserve 路径正常工作的前提。
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "seckill.redis.warm-up",
      name = "enabled",
      havingValue = "true")
  ApplicationRunner seckillStockCacheWarmUp(
      SeckillStockRepository stockRepository,
      StringRedisTemplate redisTemplate) {
    return args -> {
      var stocks = stockRepository.findAll();
      stocks.forEach(stock -> {
        String key = "seckill:stock:" + stock.getSkuId() + ":available";
        boolean loaded = Boolean.TRUE.equals(
            redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(stock.getAvailableStock())));
        if (loaded) {
          log.info("Seckill cache warm-up: loaded skuId={} available={}", stock.getSkuId(), stock.getAvailableStock());
        } else {
          log.debug("Seckill cache warm-up: skipped skuId={} (key already exists)", stock.getSkuId());
        }
      });
      log.info("Seckill cache warm-up finished: {} SKU(s) processed", stocks.size());
    };
  }
}
