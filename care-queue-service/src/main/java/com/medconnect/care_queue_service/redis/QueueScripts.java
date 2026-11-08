package com.medconnect.care_queue_service.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

/** Lua scripts from {@code src/main/resources/scripts}; see each script header for its KEYS/ARGV contract. */
@Configuration(proxyBeanMethods = false)
public class QueueScripts {

	@Bean
	public RedisScript<String> enqueueOrMoveScript() {
		return script("enqueue_or_move.lua");
	}

	@Bean
	public RedisScript<String> removeScript() {
		return script("remove.lua");
	}

	@Bean
	public RedisScript<String> doctorStatusScript() {
		return script("doctor_status.lua");
	}

	@Bean
	public RedisScript<String> claimScript() {
		return script("claim.lua");
	}

	@Bean
	public RedisScript<String> confirmScript() {
		return script("confirm.lua");
	}

	@Bean
	public RedisScript<String> requeueExpiredScript() {
		return script("requeue_expired.lua");
	}

	static RedisScript<String> script(String name) {
		return RedisScript.of(new ClassPathResource("scripts/" + name), String.class);
	}
}
