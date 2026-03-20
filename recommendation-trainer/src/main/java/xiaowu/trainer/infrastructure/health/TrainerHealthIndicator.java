package xiaowu.trainer.infrastructure.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import xiaowu.trainer.application.service.AlsTrainingService;

@Component
@RequiredArgsConstructor
public class TrainerHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final AlsTrainingService alsTrainingService;

    @Value("${recommendation.als.model-name:user-cf-als}")
    private String modelName;

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        // 数据库连通性检查
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            builder.withDetail("database", "connected");
        } catch (Exception e) {
            return Health.down()
                    .withDetail("database", "disconnected")
                    .withDetail("error", e.getMessage())
                    .build();
        }

        // ALS 训练状态
        var status = alsTrainingService.getStatus();
        builder.withDetail("modelName", status.modelName())
                .withDetail("trainingState", status.state().name())
                .withDetail("activeModelVersion", status.activeModelVersion());

        if (status.startedAt() != null) {
            builder.withDetail("lastTrainingStartedAt", status.startedAt().toString());
        }
        if (status.finishedAt() != null) {
            builder.withDetail("lastTrainingFinishedAt", status.finishedAt().toString());
        }

        return builder.build();
    }
}
