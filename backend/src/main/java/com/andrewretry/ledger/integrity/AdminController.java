package com.andrewretry.ledger.integrity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.andrewretry.ledger.integrity.dto.HoldStatus;
import com.andrewretry.ledger.integrity.dto.ReleaseHoldRequest;

import jakarta.validation.Valid;

/** Guarded by AdminKeyFilter, like everything under /admin. */
@RestController
public class AdminController {

	private final IntegrityHoldService holds;

	public AdminController(IntegrityHoldService holds) {
		this.holds = holds;
	}

	@GetMapping("/admin/integrity-hold")
	HoldStatus hold() {
		return holds.status();
	}

	@PostMapping("/admin/integrity-hold/release")
	HoldStatus release(@Valid @RequestBody ReleaseHoldRequest request) {
		return holds.release(request.reason());
	}

}
