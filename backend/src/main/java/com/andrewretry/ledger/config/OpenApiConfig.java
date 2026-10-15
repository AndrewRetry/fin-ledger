package com.andrewretry.ledger.config;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** Swagger UI at /swagger-ui.html. Request examples live on the request records (@Schema). */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	private static final String ADMIN_KEY = "adminKey";

	@Bean
	OpenAPI ledgerApi() {
		return new OpenAPI()
				.info(new Info().title("Internal Ledger API").version("1.0.0").description("""
						Double-entry ledger: atomic, idempotent transfers, reversals, multi-currency through FX \
						liquidity wallets, a hash-chained journal, integrity checks and month-end reconciliation. \
						Amounts are integers in minor units (2500 = SGD 25.00). Demo accounts: Alice \
						8398f93a-24c5-3661-8e9a-33d54273d824 (SGD), Bob 9f75da44-22c8-31e4-9a7d-2bd07518cc4a (SGD), \
						Carol f5da7969-bb24-307c-ad5f-82b42413fc67 (USD), Kenji 413efdce-9786-318b-be29-69819a82091a (JPY)."""))
				.components(new Components().addSecuritySchemes(ADMIN_KEY, new SecurityScheme()
						.type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Admin-Key")));
	}

	/** Idempotency-Key is required on every money-moving POST; everything under /admin needs the admin key. */
	@Bean
	OpenApiCustomizer ledgerHeaders() {
		return openApi -> openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
			if (operation.getParameters() != null) {
				operation.getParameters().stream()
						.filter(parameter -> "Idempotency-Key".equals(parameter.getName()))
						.forEach(parameter -> parameter.required(true).example("demo-key-1").description(
								"Unique per request. Same key + same body replays the first result; "
										+ "same key + different body is 422."));
			}
			if (path.startsWith("/admin/")) {
				operation.addSecurityItem(new SecurityRequirement().addList(ADMIN_KEY));
			}
		}));
	}

}
