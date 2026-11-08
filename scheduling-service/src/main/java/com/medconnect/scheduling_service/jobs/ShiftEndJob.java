package com.medconnect.scheduling_service.jobs;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.medconnect.scheduling_service.availability.AvailabilityService;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Every 60 s: FREE doctors whose shift is over go OFFLINE. BUSY doctors are handled when their consultation ends. */
@Component
public class ShiftEndJob {

	private final AvailabilityService service;

	ShiftEndJob(AvailabilityService service) {
		this.service = service;
	}

	@Scheduled(fixedDelayString = "${scheduling.shift-end-interval:PT60S}")
	@SchedulerLock(name = "shift-end-job", lockAtMostFor = "PT1M")
	public void run() {
		service.expireFreeWithoutActiveShift();
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
