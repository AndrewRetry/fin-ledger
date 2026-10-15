package com.andrewretry.ledger.common;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The supported currencies and their number of decimals, read once at startup (the table is append-only). */
@Component
public class Currencies {

	private final Map<String, Integer> minorUnitsByCode;

	@Autowired
	public Currencies(JdbcTemplate jdbc) {
		this(load(jdbc));
	}

	public Currencies(Map<String, Integer> minorUnitsByCode) {
		this.minorUnitsByCode = Map.copyOf(minorUnitsByCode);
	}

	public boolean isSupported(String code) {
		return minorUnitsByCode.containsKey(code);
	}

	public int minorUnits(String code) {
		Integer units = minorUnitsByCode.get(code);
		if (units == null) {
			throw new IllegalArgumentException("Unsupported currency: " + code);
		}
		return units;
	}

	private static Map<String, Integer> load(JdbcTemplate jdbc) {
		return jdbc.query("select code, minor_units from currencies", (rs, row) -> Map.entry(rs.getString(1), rs.getInt(2)))
				.stream()
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

}
