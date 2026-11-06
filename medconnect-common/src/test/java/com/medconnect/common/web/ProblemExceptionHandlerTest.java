package com.medconnect.common.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

class ProblemExceptionHandlerTest {

	private static final String CORRELATION_ID = "d4a6c0e2-7f3b-4a58-9c1d-0b2e6f8a1c33";

	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
		.setControllerAdvice(new SecurityProblemHandler(), new ProblemExceptionHandler())
		.addFilters(new CorrelationIdFilter())
		.build();

	@Test
	void validationErrorIs422ProblemJson() throws Exception {
		mvc.perform(post("/test").contentType(MediaType.APPLICATION_JSON).content("{\"painLevel\": 11}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(422))
			.andExpect(jsonPath("$.errors[0].field").value("painLevel"))
			.andExpect(jsonPath("$.errors[0].message").isNotEmpty());
	}

	@Test
	void malformedJsonIs400() throws Exception {
		mvc.perform(post("/test").contentType(MediaType.APPLICATION_JSON).content("{not json"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void apiExceptionMapsToProblemWithCorrelationId() throws Exception {
		mvc.perform(post("/conflict").header(CorrelationIdFilter.HEADER, CORRELATION_ID))
			.andExpect(status().isConflict())
			.andExpect(header().string(CorrelationIdFilter.HEADER, CORRELATION_ID))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.title").value("Active consultation exists"))
			.andExpect(jsonPath("$.detail").value("Patient already has an active consultation"))
			.andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
	}

	@Test
	void missingCorrelationIdIsGenerated() throws Exception {
		mvc.perform(post("/conflict"))
			.andExpect(header().string(CorrelationIdFilter.HEADER, matchesPattern("[0-9a-f-]{36}")));
	}

	@Test
	void unexpectedErrorIs500WithoutInternals() throws Exception {
		mvc.perform(post("/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").doesNotExist());
	}

	@Test
	void methodSecurityDenialIs403NotServerError() throws Exception {
		mvc.perform(post("/denied"))
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(403));
	}

	@Test
	void everyProblemHasType() throws Exception {
		mvc.perform(post("/conflict")).andExpect(jsonPath("$.type").value("about:blank"));
		mvc.perform(post("/boom")).andExpect(jsonPath("$.type").value("about:blank"));
		mvc.perform(post("/denied")).andExpect(jsonPath("$.type").value("about:blank"));
		mvc.perform(post("/unauth")).andExpect(jsonPath("$.type").value("about:blank"));
		mvc.perform(post("/test").contentType(MediaType.APPLICATION_JSON).content("{\"painLevel\": 11}"))
			.andExpect(jsonPath("$.type").value("about:blank"));
		mvc.perform(post("/test").contentType(MediaType.APPLICATION_JSON).content("{not json"))
			.andExpect(jsonPath("$.type").value("about:blank"));
	}

	@Test
	void infrastructureFailuresAre503() throws Exception {
		for (String path : new String[] { "/infra/resource", "/infra/jdbc", "/infra/tx", "/infra/transient",
				"/infra/timeout", "/infra/amqp" }) {
			mvc.perform(post(path))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.status").value(503));
		}
	}

	record Body(@Min(1) @Max(10) int painLevel) {
	}

	@RestController
	static class TestController {

		@PostMapping("/test")
		Body test(@Valid @RequestBody Body body) {
			return body;
		}

		@PostMapping("/conflict")
		void conflict() {
			throw new ApiException(HttpStatus.CONFLICT, "Active consultation exists", "Patient already has an active consultation");
		}

		@PostMapping("/denied")
		void denied() {
			throw new AccessDeniedException("not the owner");
		}

		@PostMapping("/unauth")
		void unauth() {
			throw new org.springframework.security.authentication.BadCredentialsException("x");
		}

		@PostMapping("/infra/resource")
		void resource() {
			throw new org.springframework.dao.DataAccessResourceFailureException("db down");
		}

		@PostMapping("/infra/jdbc")
		void jdbc() {
			throw new org.springframework.jdbc.CannotGetJdbcConnectionException("no conn");
		}

		@PostMapping("/infra/tx")
		void txFail() {
			throw new org.springframework.transaction.CannotCreateTransactionException("no tx");
		}

		@PostMapping("/infra/transient")
		void transientFail() {
			throw new org.springframework.dao.TransientDataAccessException("t") {
			};
		}

		@PostMapping("/infra/timeout")
		void timeout() {
			throw new org.springframework.dao.QueryTimeoutException("slow");
		}

		@PostMapping("/infra/amqp")
		void amqp() {
			throw new org.springframework.amqp.AmqpConnectException(new java.io.IOException("broker down"));
		}

		@PostMapping("/boom")
		void boom() {
			throw new IllegalStateException("secret internals");
		}
	}
}
