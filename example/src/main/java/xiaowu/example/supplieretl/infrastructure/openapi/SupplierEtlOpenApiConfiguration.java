package xiaowu.example.supplieretl.infrastructure.openapi;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

@Configuration(proxyBeanMethods = false)
public class SupplierEtlOpenApiConfiguration {

  @Bean
  GroupedOpenApi supplierEtlGroupedOpenApi() {
    return GroupedOpenApi.builder()
        .group("supplier-etl")
        .pathsToMatch("/api/examples/suppliers/**", "/api/examples/data-sources/**")
        .build();
  }

  @Bean
  OpenAPI exampleOpenApi() {
    return new OpenAPI()
        .info(new Info()
            .title("Example Learning Module API")
            .version("v1")
            .description("Supplier ETL scheduling demo APIs for Swagger UI and Apifox import.")
            .license(new License().name("Internal Learning Use")));
  }
}
