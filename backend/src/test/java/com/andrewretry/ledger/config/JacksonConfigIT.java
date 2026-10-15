package com.andrewretry.ledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.andrewretry.ledger.support.IntegrationTest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** The strict-JSON promises of AGENTS.md §4, proved on the mapper Spring MVC actually uses. */
@IntegrationTest
class JacksonConfigIT {

	record Probe(long amount) {
	}

	record Stamp(Instant at) {
	}

	@Autowired
	JsonMapper mapper;

	@Test
	void acceptsAnIntegerAmount() {
		assertThat(mapper.readValue("{\"amount\": 100}", Probe.class).amount()).isEqualTo(100);
	}

	@Test
	void rejectsAFractionalAmount() {
		assertThatThrownBy(() -> mapper.readValue("{\"amount\": 10.5}", Probe.class)).isInstanceOf(JacksonException.class);
	}

	@Test
	void rejectsAnAmountSentAsAString() {
		assertThatThrownBy(() -> mapper.readValue("{\"amount\": \"100\"}", Probe.class)).isInstanceOf(JacksonException.class);
	}

	@Test
	void rejectsAnUnknownField() {
		assertThatThrownBy(() -> mapper.readValue("{\"amount\": 1, \"extra\": true}", Probe.class))
				.isInstanceOf(JacksonException.class);
	}

	@Test
	void writesInstantsAsIsoStrings() {
		String json = mapper.writeValueAsString(new Stamp(Instant.parse("2026-10-14T03:00:00.123456Z")));

		assertThat(json).isEqualTo("{\"at\":\"2026-10-14T03:00:00.123456Z\"}");
	}

}
