package com.medconnect.pharmacy_provider;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Simulates a pharmacy outage: while enabled the listener fails every delivery. Internal only, not routed by the gateway. */
@RestController
@RequestMapping("/admin/outage")
class OutageToggleController {

	private final AtomicBoolean enabled = new AtomicBoolean();

	record Toggle(boolean enabled) {
	}

	@PostMapping
	Toggle set(@RequestBody Toggle body) {
		enabled.set(body.enabled());
		return body;
	}

	boolean outage() {
		return enabled.get();
	}
}
