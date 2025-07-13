package xiaowu.backed.domain.eventburial.valueobject;

import lombok.Getter;

import java.util.Objects;

/**
 * 评分值对象
 * 封装评分逻辑和验证规则
 */
@Getter
public final class Rating {
    private static final double MIN_RATING = 1.0;
    private static final double MAX_RATING = 5.0;

    private final double value;

    private Rating(double value) {
        this.value = value;
    }

    /**
     * 创建评分值对象的工厂方法
     * 包含完整的验证逻辑
     */
    public static Rating of(double value) {
        if (value < MIN_RATING || value > MAX_RATING) {
            throw new IllegalArgumentException(
                String.format("评分必须在%.1f到%.1f之间，当前值: %.1f",
                    MIN_RATING, MAX_RATING, value));
        }

        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("评分不能为NaN或无穷大");
        }

        return new Rating(value);
    }

    /**
     * 创建空评分（用于没有评分的行为）
     */
    public static Rating empty() {
        return new Rating(0.0);
    }

    public double getValue() { return value; }
    public boolean isEmpty() { return value == 0.0; }

    /**
     * 将评分标准化到0-1区间，用于机器学习算法
     */
    public double normalize() {
        if (isEmpty()) return 0.0;
        return (value - MIN_RATING) / (MAX_RATING - MIN_RATING);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Rating rating = (Rating) o;
        return Double.compare(rating.value, value) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return isEmpty() ? "无评分" : String.format("%.1f", value);
    }
}
