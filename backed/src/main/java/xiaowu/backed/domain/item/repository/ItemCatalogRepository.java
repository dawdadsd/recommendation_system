package xiaowu.backed.domain.item.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import xiaowu.backed.domain.item.entity.ItemCatalog;

/**
 * 商品信息数据访问
 *
 * <p>
 * AI 推荐时需要查询商品详情，生成个性化推荐理由。
 * 支持按品类、价格区间、itemId 列表查询。
 *
 * @author xiaowu
 */
public interface ItemCatalogRepository extends JpaRepository<ItemCatalog, Long> {

    Optional<ItemCatalog> findByItemId(Long itemId);

    List<ItemCatalog> findByCategory(String category);

    List<ItemCatalog> findByCategoryAndPriceBetween(String category, BigDecimal minPrice, BigDecimal maxPrice);

    List<ItemCatalog> findByItemIdIn(List<Long> itemIds);
}
