package com.andrewretry.ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import com.andrewretry.ledger.support.Api;
import com.andrewretry.ledger.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

@IntegrationTest
class OpenApiDocsIT {

	private final Api api;

	OpenApiDocsIT(@Value("${local.server.port}") int port) {
		this.api = new Api(port);
	}

	@Test
	void documentsTheRequiredIdempotencyKeyAndTheAdminKey() {
		JsonNode docs = api.get("/v3/api-docs").json();

		JsonNode key = idempotencyKey(docs.path("paths").path("/transactions").path("post"));
		assertThat(key.path("required").asBoolean()).isTrue();
		assertThat(docs.path("paths").path("/admin/fx-rates").path("post").path("security").get(0).has("adminKey")).isTrue();
		assertThat(docs.path("components").path("securitySchemes").path("adminKey").path("name").asString())
				.isEqualTo("X-Admin-Key");
	}

	@Test
	void servesSwaggerUi() {
		assertThat(api.get("/swagger-ui/index.html").status()).isEqualTo(200);
	}

	private static JsonNode idempotencyKey(JsonNode operation) {
		for (JsonNode parameter : operation.path("parameters")) {
			if (parameter.path("name").asString().equals("Idempotency-Key")) {
				return parameter;
			}
		}
		throw new AssertionError("Idempotency-Key not documented");
	}

}
