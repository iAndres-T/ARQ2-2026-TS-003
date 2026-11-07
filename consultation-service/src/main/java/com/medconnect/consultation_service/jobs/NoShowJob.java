package com.medconnect.consultation_service.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.medconnect.consultation_service.app.ConsultationService;
import com.medconnect.consultation_service.domain.ConsultationRepository;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** R-F4-05 / S-F4-09: ASSIGNED consultations whose patient did not join within the timeout become NO_SHOW. */
@Component
public class NoShowJob {

	private static final Logger log = LoggerFactory.getLogger(NoShowJob.class);

	private final ConsultationRepository repo;
	private final ConsultationService service;
	private final Duration timeout;

	NoShowJob(ConsultationRepository repo, ConsultationService service,
			@Value("${medconnect.consultation.no-show-timeout:300s}") Duration timeout) {
		this.repo = repo;
		this.service = service;
		this.timeout = timeout;
	}

	@Scheduled(fixedDelayString = "${medconnect.consultation.no-show-interval:PT15S}")
	@SchedulerLock(name = "consultation-no-show", lockAtMostFor = "PT1M")
	public void run() {
		Instant cutoff = Instant.now().minus(timeout);
		for (UUID id : repo.findNoShowCandidates(cutoff)) {
			try {
				if (service.markNoShow(id, cutoff)) {
					log.info("Consultation {} became NO_SHOW", id);
				}
			}
			catch (RuntimeException ex) {
				log.warn("No-show for consultation {} failed, retried on the next run: {}", id, ex.toString());
			}
		}
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
