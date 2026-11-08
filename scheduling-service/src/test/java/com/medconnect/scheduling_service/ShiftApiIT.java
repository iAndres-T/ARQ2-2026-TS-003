package com.medconnect.scheduling_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.JsonNode;

@SpringBootTest
@Import(TestContainers.class)
class ShiftApiIT extends ApiSupport {

	static final Instant T0 = Instant.parse("2030-01-01T08:00:00Z");

	@Test
	void createsShiftAndListsItByRange() throws Exception {
		UUID d = UUID.randomUUID();
		MockHttpServletResponse r = createShift(d, T0, T0.plus(8, ChronoUnit.HOURS), admin());
		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(JSON.readTree(r.getContentAsString()).get("shiftId").asString()).isNotBlank();

		MockHttpServletResponse l = mvc.perform(get("/api/v1/shifts").param("from", T0.toString())
			.param("to", T0.plus(1, ChronoUnit.DAYS).toString()).param("doctorId", d.toString()).with(doctor(UUID.randomUUID())))
			.andReturn().getResponse();
		assertThat(l.getStatus()).isEqualTo(200);
		assertThat(JSON.readTree(l.getContentAsString()).get("items")).hasSize(1);
		assertThat(mvc.perform(get("/api/v1/shifts").with(doctor(UUID.randomUUID()))).andReturn().getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void overlappingShiftIs409() throws Exception {
		UUID d = UUID.randomUUID();
		assertThat(createShift(d, T0, T0.plus(8, ChronoUnit.HOURS), admin()).getStatus()).isEqualTo(201);
		assertThat(createShift(d, T0.plus(4, ChronoUnit.HOURS), T0.plus(12, ChronoUnit.HOURS), admin()).getStatus()).isEqualTo(409);
		// back-to-back is not an overlap
		assertThat(createShift(d, T0.plus(8, ChronoUnit.HOURS), T0.plus(16, ChronoUnit.HOURS), admin()).getStatus()).isEqualTo(201);
	}

	@Test
	void concurrentOverlappingPostsCreateExactlyOne() throws Exception {
		UUID d = UUID.randomUUID();
		var pool = Executors.newFixedThreadPool(6);
		try {
			var results = pool.invokeAll(IntStream.range(0, 6).<Callable<Integer>>mapToObj(
					i -> () -> createShift(d, T0, T0.plus(8, ChronoUnit.HOURS), admin()).getStatus()).toList());
			long created = 0;
			for (var f : results) {
				created += f.get() == 201 ? 1 : 0;
			}
			assertThat(created).isEqualTo(1);
			assertThat(jdbc.queryForObject("select count(*) from shifts where doctor_id = ?", Integer.class, d)).isEqualTo(1);
		}
		finally {
			pool.shutdown();
		}
	}

	@Test
	void endsBeforeStartsIs422() throws Exception {
		assertThat(createShift(UUID.randomUUID(), T0, T0, admin()).getStatus()).isEqualTo(422);
		assertThat(createShift(UUID.randomUUID(), T0, T0.minusSeconds(60), admin()).getStatus()).isEqualTo(422);
	}

	@Test
	void createShiftWithoutScopeIs403() throws Exception {
		assertThat(createShift(UUID.randomUUID(), T0, T0.plus(8, ChronoUnit.HOURS), doctor(UUID.randomUUID())).getStatus()).isEqualTo(403);
	}

	@Test
	void firstShiftCreatesOfflineAvailability() throws Exception {
		UUID d = UUID.randomUUID();
		createShift(d, T0, T0.plus(8, ChronoUnit.HOURS), admin());
		JsonNode a = availability(d);
		assertThat(a.get("status").asString()).isEqualTo("OFFLINE");
		assertThat(a.get("shiftId").isNull()).isTrue();
	}
}
