package org.openphc.cce.admin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI adminServiceOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("CCE Admin Service")
                .description("Users, roles, menus and API permissions for the CCE platform")
                .version("v1"));
  }
}
