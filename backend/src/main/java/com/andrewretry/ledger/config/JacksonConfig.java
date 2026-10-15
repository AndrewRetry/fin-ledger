package com.andrewretry.ledger.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;

/**
 * Strict JSON for a money API: an unknown field, {@code 10.5} for an integer, or {@code "100"} for a number is a
 * 400, never a silent conversion. The three settings are made here, not only in application.yaml, so the behaviour
 * does not depend on property names that changed between Jackson versions.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

	@Bean
	JsonMapperBuilderCustomizer strictJson() {
		return builder -> builder
				.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
				.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
	}

}
