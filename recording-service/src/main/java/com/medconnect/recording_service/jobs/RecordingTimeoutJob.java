package com.medconnect.recording_service.jobs;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.medconnect.common.messaging.MessagingTopology;
import com.medconnect.common.messaging.OutboxWriter;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Every 60 s: recordings stuck in PROCESSING, and sessions mapped by ConsultationCompleted that never got a webhook,
 * become FAILED + RecordingFailed. Partial reconciliation only: storage is not scanned for orphaned objects.
 */
@Component
public class RecordingTimeoutJob {

	public static final String REASON = "provider webhook timeout";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final OutboxWriter outbox;
	private final Duration timeout;

	RecordingTimeoutJob(JdbcTemplate jdbc, TransactionTemplate tx, OutboxWriter outbox,
			@Value("${medconnect.recording.processing-timeout:30m}") Duration timeout) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.outbox = outbox;
		this.timeout = timeout;
	}

	@Scheduled(fixedDelayString = "${medconnect.recording.timeout-interval:PT60S}")
	@SchedulerLock(name = "recording-timeout-job", lockAtMostFor = "PT1M")
	public void run() {
		tx.executeWithoutResult(s -> {
			Timestamp cutoff = Timestamp.from(Instant.now().minus(timeout));
			List<Map<String, Object>> stuck = jdbc.queryForList(
					"select id, consultation_id, attempts from recordings where status = 'PROCESSING' and created_at < ? for update skip locked", cutoff);
			for (Map<String, Object> r : stuck) {
				int attempts = Math.max(1, ((Number) r.get("attempts")).intValue());
				jdbc.update("update recordings set status = 'FAILED', attempts = ?, failure_reason = ? where id = ?", attempts, REASON, r.get("id"));
				emit((UUID) r.get("id"), (UUID) r.get("consultation_id"), attempts);
			}
			// completed sessions with no webhook at all (no recordings row for the consultation)
			List<UUID> silent = jdbc.queryForList("select distinct consultation_id from session_map m where created_at < ? "
					+ "and not exists (select 1 from recordings r where r.consultation_id = m.consultation_id)", UUID.class, cutoff);
			for (UUID cid : silent) {
				UUID id = UUID.randomUUID();
				jdbc.update("insert into recordings(id, consultation_id, provider_recording_id, status, attempts, failure_reason) "
						+ "values (?, ?, ?, 'FAILED', 1, ?)", id, cid, "timeout-" + id, REASON);
				emit(id, cid, 1);
			}
		});
	}

	private void emit(UUID recordingId, UUID consultationId, int attempts) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("recordingId", recordingId);
		payload.put("consultationId", consultationId);
		payload.put("reason", REASON);
		payload.put("attempts", attempts);
		payload.put("failedAt", Instant.now());
		outbox.append(MessagingTopology.EVENTS_EXCHANGE, "recording.failed", "RecordingFailed", consultationId, 1, null, payload);
	}

	@Configuration
	@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
	static class LockConfig {

		@Bean
		LockProvider lockProvider(DataSource dataSource) {
			return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
				.withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build());
		}
	}
}
