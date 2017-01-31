/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.cloud.deployer.spi.cloudfoundry;

import java.time.Duration;
import java.util.function.Function;

import org.cloudfoundry.util.DelayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Utilities for error handling
 */
public class ErrorHandlingUtils {

	private static final Logger logger = LoggerFactory.getLogger(ErrorHandlingUtils.class);

	/**
	 * To be used in order to retry the status operation for an application or task.
	 * @param id The application id or the task id
	 * @param requestTimeout How long in milliseconds to wait or a response from the server before starting the retry logic
	 * @param initialRetryDelay the initial delay in milliseconds for the retry operation
	 * @param statusTimeout the status timeout in millis which is used for the total max retry time.  statusTimeout/2 is the max
	 *                      time for a retry interval
	 * @param <T> The type of status object being queried for, usually AppStatus or TaskStatus
	 * @return The function that executes the retry logic around for determining App or Task Status
	 */
	public static <T> Function<Mono<T>, Mono<T>> statusRetry(String id, long requestTimeout, long initialRetryDelay, long statusTimeout) {
		return m -> m.timeout(Duration.ofMillis(requestTimeout))
			.doOnError(e -> logger.error(String.format("Error getting status for %s within %s, Retrying operation.", id, requestTimeout), e))
			.retryWhen(DelayUtils.exponentialBackOffError(
				Duration.ofMillis(initialRetryDelay), //initial retry delay
				Duration.ofMillis(statusTimeout/2), // max retry delay
				Duration.ofMillis(statusTimeout)) // max total retry time
				.andThen(retries ->  Flux.from(retries).doOnComplete(() ->
					logger.info("Successfully retried getStatus operation status [{}] for {}", id))))
			.doOnError(e -> logger.error(String.format("Retry operation on getStatus failed for %s", id), e));
	}
}
