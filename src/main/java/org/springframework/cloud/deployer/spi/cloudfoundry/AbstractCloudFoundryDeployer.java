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

import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.BUILDPACK_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.USE_SPRING_APPLICATION_JSON_KEY;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.AbstractCloudFoundryException;
import org.cloudfoundry.util.DelayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.util.ByteSizeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * Base class dealing with configuration overrides on a per-deployment basis, as well as common code for apps and tasks.
 *
 * @author Eric Bottard
 */
class AbstractCloudFoundryDeployer {

	protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	final CloudFoundryDeploymentProperties deploymentProperties;

	private final Logger logger = LoggerFactory.getLogger(getClass());

	AbstractCloudFoundryDeployer(CloudFoundryDeploymentProperties deploymentProperties) {
		this.deploymentProperties = deploymentProperties;
		Hooks.onOperator(op -> op.operatorStacktrace());
	}

	public Map<String, String> getApplicationProperties(String deploymentId, AppDeploymentRequest request) {
		Map<String, String> applicationProperties = getSanitizedApplicationProperties(deploymentId, request);

		if (!useSpringApplicationJson(request)) {
			return applicationProperties;
		}

		try {
			return Collections.singletonMap("SPRING_APPLICATION_JSON", OBJECT_MAPPER.writeValueAsString(applicationProperties));
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	public Map<String, String> getCommandLineArguments(AppDeploymentRequest request) {
		if (request.getCommandlineArguments().isEmpty()) {
			return Collections.emptyMap();
		}

		String argumentsAsString = request.getCommandlineArguments().stream()
			.collect(Collectors.joining(" "));
		String yaml = new Yaml().dump(Collections.singletonMap("arguments", argumentsAsString));

		return Collections.singletonMap("JBP_CONFIG_JAVA_MAIN", yaml);
	}

	public Map<String, String> getEnvironmentVariables(String deploymentId, AppDeploymentRequest request) {
		Map<String, String> envVariables = new HashMap<>();
		envVariables.putAll(getApplicationProperties(deploymentId, request));
		envVariables.putAll(getCommandLineArguments(request));
		String group = request.getDeploymentProperties().get(AppDeployer.GROUP_PROPERTY_KEY);
		if (group != null) {
			envVariables.put("SPRING_CLOUD_APPLICATION_GROUP", group);
		}
		return envVariables;
	}

	public Map<String, String> getSanitizedApplicationProperties(String deploymentId, AppDeploymentRequest request) {
		Map<String, String> applicationProperties = new HashMap<>(request.getDefinition().getProperties());

		// Remove server.port as CF assigns a port for us, and we don't want to override that
		Optional.ofNullable(applicationProperties.remove("server.port"))
			.ifPresent(port -> logger.warn("Ignoring 'server.port={}' for app {}, as Cloud Foundry will assign a local dynamic port. Route to the app will use port 80.", port, deploymentId));

		return applicationProperties;
	}

	public int instances(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(AppDeployer.COUNT_PROPERTY_KEY))
			.map(Integer::parseInt)
			.orElse(this.deploymentProperties.getInstances());
	}

	public boolean useSpringApplicationJson(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(USE_SPRING_APPLICATION_JSON_KEY))
			.map(Boolean::valueOf)
			.orElse(this.deploymentProperties.isUseSpringApplicationJson());
	}

	int memory(AppDeploymentRequest request) {
		String withUnit = request.getDeploymentProperties()
			.getOrDefault(AppDeployer.MEMORY_PROPERTY_KEY, this.deploymentProperties.getMemory());
		return (int) ByteSizeUtils.parseToMebibytes(withUnit);
	}

	Set<String> servicesToBind(AppDeploymentRequest request) {
		Set<String> services = new HashSet<>();
		services.addAll(this.deploymentProperties.getServices());
		services.addAll(StringUtils.commaDelimitedListToSet(request.getDeploymentProperties().get(SERVICES_PROPERTY_KEY)));
		return services;
	}

	int diskQuota(AppDeploymentRequest request) {
		String withUnit = request.getDeploymentProperties()
			.getOrDefault(AppDeployer.DISK_PROPERTY_KEY, this.deploymentProperties.getDisk());
		return (int) ByteSizeUtils.parseToMebibytes(withUnit);
	}

	String buildpack(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(BUILDPACK_PROPERTY_KEY))
			.orElse(this.deploymentProperties.getBuildpack());
	}

	Predicate<Throwable> isNotFoundError() {
		return t -> t instanceof AbstractCloudFoundryException && ((AbstractCloudFoundryException) t).getStatusCode() == HttpStatus.NOT_FOUND.value();
	}
	/**
	 * To be used in order to retry the status operation for an application or task.
	 * @param id The application id or the task id
	 * @param <T> The type of status object being queried for, usually AppStatus or TaskStatus
	 * @return The function that executes the retry logic around for determining App or Task Status
	 */
	<T> Function<Mono<T>, Mono<T>> statusRetry(String id) {
		long statusTimeout = this.deploymentProperties.getStatusTimeout();
		long requestTimeout = Math.round(statusTimeout * 0.40); // wait 500ms with default status timeout of 2000ms
		long initialRetryDelay = Math.round(statusTimeout * 0.10); // wait 200ms with status timeout of 2000ms

		if (requestTimeout < 500L) {
			logger.info("Computed statusRetry Request timeout = {} ms is below 500ms minimum value.  Setting to 500ms", requestTimeout);
			requestTimeout = 500L;
		}
		final long requestTimeoutToUse = requestTimeout;
		return m -> m.timeout(Duration.ofMillis(requestTimeoutToUse))
			.doOnError(e -> logger.error(String.format("Error getting status for %s within %sms, Retrying operation.", id, requestTimeoutToUse)))
			.retryWhen(DelayUtils.exponentialBackOffError(
				Duration.ofMillis(initialRetryDelay), //initial retry delay
				Duration.ofMillis(statusTimeout / 2), // max retry delay
				Duration.ofMillis(statusTimeout)) // max total retry time
				.andThen(retries -> Flux.from(retries).doOnComplete(() ->
					logger.info("Successfully retried getStatus operation status [{}] for {}", id))))
			.doOnError(e -> logger.error(String.format("Retry operation on getStatus failed for %s.  Max retry time %sms", id, statusTimeout)));
	}



}
