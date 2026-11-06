package com.medconnect.common.messaging;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration(afterName = {
		"org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
		"org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration" })
@ConditionalOnClass(RabbitTemplate.class)
@EnableConfigurationProperties(MessagingProperties.class)
@EnableScheduling
public class MessagingAutoConfiguration {

	@Bean
	Declarables medconnectPlatformTopology(MessagingProperties properties) {
		return MessagingTopology.declarables(properties.retryDelays());
	}

	@Bean
	TieredRetryRecoverer tieredRetryRecoverer(RabbitTemplate rabbit, MessagingProperties properties) {
		return new TieredRetryRecoverer(rabbit, properties.retryDelays(), properties.deadLetterExchanges());
	}

	/** Publisher confirms are required by the outbox relay; listener factories get the tiered-retry advice. */
	@Bean
	static BeanPostProcessor medconnectRabbitPostProcessor(ObjectProvider<TieredRetryRecoverer> recoverer) {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (bean instanceof CachingConnectionFactory cf) {
					cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
				}
				if (bean instanceof AbstractRabbitListenerContainerFactory<?> factory) {
					factory.setAdviceChain(new RecoveringAdvice((message, cause) -> recoverer.getObject().recover(message, cause)));
				}
				return bean;
			}
		};
	}
}
