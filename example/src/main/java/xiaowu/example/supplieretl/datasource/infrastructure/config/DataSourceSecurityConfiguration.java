package xiaowu.example.supplieretl.datasource.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConnectionTestSecurityProperties.class)
public class DataSourceSecurityConfiguration {
}
