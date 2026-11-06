package com.medconnect.common.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param retryDelays backoff per retry (spec §3: 5 s, 30 s, 2 min, 10 min, 1 h)
 * @param outboxPollInterval delay between outbox relay polls
 * @param deadLetterExchanges queue name to exchange that receives its final failures (routing key = the queue's DLQ
 *        name) instead of the default exchange; empty by default
 */
@ConfigurationProperties("medconnect.messaging")
public record MessagingProperties(@DefaultValue({ "5s", "30s", "2m", "10m", "1h" }) List<Duration> retryDelays,
		@DefaultValue("200ms") Duration outboxPollInterval,
		@DefaultValue Map<String, String> deadLetterExchanges) {
}
