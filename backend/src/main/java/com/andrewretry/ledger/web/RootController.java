package com.andrewretry.ledger.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;

/** Opening the service in a browser lands on the API docs. */
@RestController
@Hidden
public class RootController {

	@GetMapping("/")
	ResponseEntity<Void> root() {
		return ResponseEntity.status(302).location(URI.create("/swagger-ui.html")).build();
	}

}
