package com.itau.transaction.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI transactionAuthorizationOpenApi() {
        return new OpenAPI().info(new Info()
                .title("API de Autorização de Transações")
                .description("Autoriza crédito e débito em contas bancárias, consumindo aberturas de conta via AWS SQS.")
                .version("v1"));
    }
}
