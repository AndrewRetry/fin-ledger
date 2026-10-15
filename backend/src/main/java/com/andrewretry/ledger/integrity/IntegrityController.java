package com.andrewretry.ledger.integrity;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.andrewretry.ledger.integrity.dto.IntegrityReport;

@RestController
public class IntegrityController {

	private final IntegrityService integrity;

	public IntegrityController(IntegrityService integrity) {
		this.integrity = integrity;
	}

	/** Runs every check now. Also runs on a schedule when ledger.jobs.enabled. */
	@PostMapping("/integrity/checks")
	IntegrityReport runChecks() {
		return integrity.run();
	}

	@GetMapping("/integrity/runs/{id}")
	IntegrityReport storedRun(@PathVariable UUID id) {
		return integrity.storedRun(id);
	}

}
