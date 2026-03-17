package xiaowu.backed.domain.item.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 商品信息
 *
 * <p>
 * AI 对话时需要知道商品详情，才能生成有意义的推荐理由。
 * 包含商品名称、品类、价格、标签和描述。
 *
 * @author xiaowu
 */
@Entity
@Table(name = "item_catalog", indexes = {
        @Index(name = "idx_category", columnList = "category"),
        @Index(name = "idx_price", columnList = "price")
})
@Getter
@Setter
@NoArgsConstructor
public class ItemCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long itemId;

    @Column(nullable = false, length = 128)
    private String name;

    /** 商品品类：手机、耳机、运动鞋 */
    @Column(nullable = false, length = 64)
    private String category;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    /** 商品标签，逗号分隔：折叠屏,旗舰,5G */
    @Column(length = 256)
    private String tags;

    @Lob
    private String description;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @jakarta.persistence.PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
