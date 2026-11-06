package com.medconnect.common.messaging;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

/** Listener advice: on failure hand the message to the recoverer and ack it instead of requeueing. */
class RecoveringAdvice implements MethodInterceptor {

	private final MessageRecoverer recoverer;

	RecoveringAdvice(MessageRecoverer recoverer) {
		this.recoverer = recoverer;
	}

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		try {
			return invocation.proceed();
		}
		catch (Throwable ex) {
			// arguments of invokeListener(Channel, Object data): data is a Message for non-batch containers
			if (invocation.getArguments()[1] instanceof Message message) {
				recoverer.recover(message, ex);
				return null;
			}
			throw ex;
		}
	}
}
