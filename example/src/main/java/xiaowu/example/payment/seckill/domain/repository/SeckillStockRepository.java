package xiaowu.example.payment.seckill.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import xiaowu.example.payment.seckill.domain.entity.SeckillStock;

public interface SeckillStockRepository {

  Optional<SeckillStock> findBySkuId(Long skuId);

  boolean reserveStock(Long skuId, long expectedVersion, LocalDateTime updatedAt);

  boolean confirmSold(Long skuId, long expectedVersion, LocalDateTime updatedAt);

  boolean releaseReservedStock(Long skuId, long expectedVersion, LocalDateTime updatedAt);
}
