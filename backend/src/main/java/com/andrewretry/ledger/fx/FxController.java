package com.andrewretry.ledger.fx;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.andrewretry.ledger.fx.dto.AddFxRateRequest;
import com.andrewretry.ledger.fx.dto.FxRateResponse;

import jakarta.validation.Valid;

@RestController
public class FxController {

	private final FxRateService rates;

	public FxController(FxRateService rates) {
		this.rates = rates;
	}

	@GetMapping("/fx/rates")
	List<FxRateResponse> latestRates() {
		return rates.latestPerPair();
	}

	/** Guarded by AdminKeyFilter, like everything under /admin. */
	@PostMapping("/admin/fx-rates")
	@ResponseStatus(HttpStatus.CREATED)
	FxRateResponse addRate(@Valid @RequestBody AddFxRateRequest request) {
		return rates.add(request.base(), request.quote(), request.rate());
	}

}
