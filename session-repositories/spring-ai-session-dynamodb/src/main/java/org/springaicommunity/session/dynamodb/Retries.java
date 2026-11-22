/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.session.dynamodb;

import java.time.Duration;
import java.util.function.Supplier;

import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;

import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;

/**
 * How the repository tries again. An attempt that lost a race, or left work undone,
 * throws {@link TryAgain}; {@link #run} then runs it again after a pause that grows with
 * every attempt. The pauses are jittered, so that callers that failed together don't
 * collide again at once.
 *
 * @author Dominik Kovács
 */
final class Retries {

	/** The attempts of one call, the first one included. */
	static final int MAX_ATTEMPTS = 16;

	/**
	 * For a writer that lost a race: pauses from 1 millisecond up to 100.
	 */
	static final RetryTemplate LOST_RACES = new RetryTemplate(RetryPolicy.builder()
		// Not includes(): it also matches causes, so it would retry the failure of a
		// run nested in the attempt
		.predicate(TryAgain.class::isInstance)
		.maxRetries(MAX_ATTEMPTS - 1L)
		.delay(Duration.ofMillis(1))
		.multiplier(2)
		.jitter(Duration.ofMillis(1))
		.maxDelay(Duration.ofMillis(100))
		.build());

	/**
	 * For a batch call that sends again what DynamoDB left unprocessed, which it does
	 * when the table is busy, and what conflicted with a transaction: pauses from 16
	 * milliseconds up to a second.
	 */
	static final RetryTemplate RESENDS = new RetryTemplate(RetryPolicy.builder()
		.predicate(ex -> ex instanceof TryAgain || ex instanceof TransactionConflictException)
		.maxRetries(MAX_ATTEMPTS - 1L)
		.delay(Duration.ofMillis(16))
		.multiplier(2)
		.jitter(Duration.ofMillis(16))
		.maxDelay(Duration.ofSeconds(1))
		.build());

	private Retries() {
	}

	/**
	 * Runs the attempt until it returns, using the template's pauses. Throws an
	 * {@link IllegalStateException} with the message of {@code exhausted} if the attempt
	 * keeps failing in a way the template retries, and one that says so if the thread is
	 * interrupted during a pause, which leaves it interrupted. Any other failure of the
	 * attempt is thrown as it is.
	 */
	static <T> T run(RetryTemplate template, Retryable<T> attempt, Supplier<String> exhausted) {
		try {
			return template.execute(attempt);
		}
		catch (RetryException ex) {
			Throwable cause = ex.getCause();
			if (!template.getRetryPolicy().shouldRetry(cause)) {
				if (cause instanceof RuntimeException runtime) {
					throw runtime;
				}
				if (cause instanceof Error error) {
					throw error;
				}
				throw new IllegalStateException(cause);
			}
			if (Thread.currentThread().isInterrupted()) {
				throw new IllegalStateException("Interrupted while waiting to retry", cause);
			}
			throw new IllegalStateException(exhausted.get(), cause);
		}
	}

	/**
	 * Like {@link #run(RetryTemplate, Retryable, Supplier)}, for an attempt with no
	 * result.
	 */
	static void run(RetryTemplate template, Runnable attempt, Supplier<String> exhausted) {
		run(template, () -> {
			attempt.run();
			return Boolean.TRUE;
		}, exhausted);
	}

	/**
	 * Thrown by an attempt that lost a race or left work undone. It only signals, so it
	 * has no stack trace.
	 */
	static final class TryAgain extends RuntimeException {

		TryAgain() {
			super(null, null, false, false);
		}

	}

}
