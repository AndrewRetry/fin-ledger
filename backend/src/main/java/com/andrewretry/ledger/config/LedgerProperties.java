package com.andrewretry.ledger.config;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** The {@code ledger.*} settings of application.yaml. A blank admin key means /admin/** always answers 401. */
@ConfigurationProperties(prefix = "ledger")
@Validated
public record LedgerProperties(String adminKey, @Valid @NotNull Close close, @Valid @NotNull Integrity integrity,
		@Valid @NotNull Jobs jobs, @Valid @NotNull Seed seed) {

	public record Close(@NotNull ZoneId zone, @NotBlank String cron) {
	}

	public record Integrity(@NotNull Duration interval) {
	}

	public record Jobs(boolean enabled) {
	}

	public record Seed(@PositiveOrZero long fxLiquidityMajorUnits, boolean demoData) {
	}

}
