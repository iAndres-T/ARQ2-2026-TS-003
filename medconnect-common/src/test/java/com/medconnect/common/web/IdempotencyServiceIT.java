package com.medconnect.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.medconnect.common.TestContainersConfig;

@SpringBootTest
@Import(TestContainersConfig.class)
class IdempotencyServiceIT {

	@Autowired IdempotencyService idempotency;
	@Autowired JdbcTemplate jdbc;

	private final UUID principal = UUID.randomUUID();
	private final AtomicInteger calls = new AtomicInteger();

	@Test
	void sameKeyReplaysOriginalResponse() {
		String key = UUID.randomUUID().toString();

		ResponseEntity<?> first = idempotency.execute(key, principal, this::accepted);
		ResponseEntity<?> second = idempotency.execute(key, principal, this::accepted);

		assertThat(calls).hasValue(1);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(second.getBody().toString()).contains("call-1");
		assertThat(first.getBody().toString()).contains("call-1");
	}

	@Test
	void missingKeyIs400() {
		assertThatThrownBy(() -> idempotency.execute(null, principal, this::accepted))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
		assertThatThrownBy(() -> idempotency.execute(" ", principal, this::accepted)).isInstanceOf(ApiException.class);
		assertThat(calls).hasValue(0);
	}

	@Test
	void sameKeyFromAnotherPrincipalExecutesAgain() {
		String key = UUID.randomUUID().toString();

		idempotency.execute(key, principal, this::accepted);
		idempotency.execute(key, UUID.randomUUID(), this::accepted);

		assertThat(calls).hasValue(2);
	}

	@Test
	void failedActionReleasesKey() {
		String key = UUID.randomUUID().toString();

		assertThatThrownBy(() -> idempotency.execute(key, principal, () -> {
			throw new ApiException(HttpStatus.CONFLICT, "Conflict", "nope");
		})).isInstanceOf(ApiException.class);
		idempotency.execute(key, principal, this::accepted);

		assertThat(calls).hasValue(1);
	}

	@Test
	void errorInActionDoesNotLeaveKeyStuck() {
		String key = UUID.randomUUID().toString();

		assertThatThrownBy(() -> idempotency.execute(key, principal, () -> {
			throw new StackOverflowError("simulated");
		})).isInstanceOf(StackOverflowError.class);
		idempotency.execute(key, principal, this::accepted);

		assertThat(calls).hasValue(1);
	}

	@Test
	void actionWritesRollBackWithTheReservation() {
		String key = UUID.randomUUID().toString();
		jdbc.execute("create table if not exists idem_side_effect (key varchar(255))");

		assertThatThrownBy(() -> idempotency.execute(key, principal, () -> {
			jdbc.update("insert into idem_side_effect(key) values (?)", key);
			throw new ApiException(HttpStatus.CONFLICT, "Conflict", "nope");
		})).isInstanceOf(ApiException.class);

		assertThat(jdbc.queryForObject("select count(*) from idem_side_effect where key = ?", Integer.class, key)).isZero();
	}

	@Test
	void concurrentSameKeyWaitsAndReplays() throws Exception {
		String key = UUID.randomUUID().toString();
		Supplier<ResponseEntity<?>> slow = () -> {
			try {
				Thread.sleep(500);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return accepted();
		};

		CompletableFuture<ResponseEntity<?>> a = CompletableFuture.supplyAsync(() -> idempotency.execute(key, principal, slow));
		Thread.sleep(100);
		CompletableFuture<ResponseEntity<?>> b = CompletableFuture.supplyAsync(() -> idempotency.execute(key, principal, slow));

		assertThat(b.get().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(b.get().getBody().toString()).contains("call-1");
		assertThat(a.get().getBody().toString()).contains("call-1");
		assertThat(calls).hasValue(1);
	}

	@Test
	void keyExpiresAfter24Hours() {
		String key = UUID.randomUUID().toString();
		idempotency.execute(key, principal, this::accepted);
		jdbc.update("update idempotency_keys set created_at = now() - interval '25 hours' where key = ?", key);

		idempotency.execute(key, principal, this::accepted);

		assertThat(calls).hasValue(2);
	}

	private ResponseEntity<?> accepted() {
		return ResponseEntity.accepted().body(Map.of("result", "call-" + calls.incrementAndGet()));
	}
}
