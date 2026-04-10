package xiaowu.example.supplieretl.datasource.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaowu.example.supplieretl.datasource.application.support.DataSourceConfigMapper;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceConnection;
import xiaowu.example.supplieretl.datasource.domain.entity.DataSourceType;
import xiaowu.example.supplieretl.datasource.domain.model.MysqlDataSourceConfig;

class JdbcDataSourceConnectionRepositoryTest {

  private JdbcDataSourceConnectionRepository repository;

  @BeforeEach
  void setUp() {
    String dbName = "etl_datasource_" + UUID.randomUUID();
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "sa",
        "");
    dataSource.setDriverClassName("org.h2.Driver");

    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("""
        CREATE TABLE etl_data_source_connection (
            id               BIGINT AUTO_INCREMENT PRIMARY KEY,
            connection_name  VARCHAR(64)  NOT NULL,
            data_source_type VARCHAR(32)  NOT NULL,
            description      VARCHAR(255),
            config_json      CLOB         NOT NULL,
            created_at       TIMESTAMP    NOT NULL,
            updated_at       TIMESTAMP    NOT NULL
        )
        """);
    jdbcTemplate.execute("""
        CREATE UNIQUE INDEX uk_etl_data_source_connection_name
            ON etl_data_source_connection (connection_name)
        """);

    repository = new JdbcDataSourceConnectionRepository(
        jdbcTemplate,
        new DataSourceConfigMapper(new ObjectMapper()));
  }

  @Test
  void saveShouldPersistAndRestoreMysqlConfig() {
    DataSourceConnection connection = DataSourceConnection.create(
        "cloud-mysql",
        "Cloud MySQL connection",
        DataSourceType.MYSQL,
        new MysqlDataSourceConfig(
            "jdbc:mysql://mysql.example.com:3306/etl",
            "etl_user",
            "secret",
            null,
            null));

    DataSourceConnection saved = repository.save(connection);
    DataSourceConnection loaded = repository.findById(saved.getId()).orElseThrow();

    assertThat(saved.getId()).isNotNull();
    assertThat(loaded.getConnectionName()).isEqualTo("cloud-mysql");
    assertThat(loaded.getType()).isEqualTo(DataSourceType.MYSQL);
    assertThat(loaded.getConfig()).isInstanceOf(MysqlDataSourceConfig.class);

    MysqlDataSourceConfig config = (MysqlDataSourceConfig) loaded.getConfig();
    assertThat(config.jdbcUrl()).isEqualTo("jdbc:mysql://mysql.example.com:3306/etl");
    assertThat(config.username()).isEqualTo("etl_user");
    assertThat(config.driverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
    assertThat(config.validationQuery()).isEqualTo("SELECT 1");
  }
}
