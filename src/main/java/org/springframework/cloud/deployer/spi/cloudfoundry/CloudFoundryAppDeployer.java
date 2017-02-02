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

import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.DOMAIN_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.HEALTHCHECK_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.HOST_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.NO_ROUTE_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.ROUTE_PATH_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.USE_SPRING_APPLICATION_JSON_KEY;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.UpdateApplicationRequest;
import org.cloudfoundry.client.v2.applications.UpdateApplicationResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.ApplicationHealthCheck;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.InstanceDetail;
import org.cloudfoundry.operations.applications.PushApplicationRequest;
import org.cloudfoundry.operations.applications.StartApplicationRequest;
import org.cloudfoundry.operations.services.BindServiceInstanceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.util.StringUtils;

/**
 * A deployer that targets Cloud Foundry using the public API.
 *
 * @author Eric Bottard
 * @author Greg Turnquist
 * @author Ben Hale
 */
public class CloudFoundryAppDeployer extends AbstractCloudFoundryDeployer implements AppDeployer {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryAppDeployer.class);

	private final AppNameGenerator applicationNameGenerator;

	private final CloudFoundryClient client;

	private final CloudFoundryOperations operations;

	public CloudFoundryAppDeployer(AppNameGenerator applicationNameGenerator, CloudFoundryClient client, CloudFoundryDeploymentProperties deploymentProperties,
								   CloudFoundryOperations operations) {
		super(deploymentProperties);
		this.operations = operations;
		this.client = client;
		this.applicationNameGenerator = applicationNameGenerator;
	}

	@Override
	public String deploy(AppDeploymentRequest request) {
		logger.trace("Entered deploy: Deploying AppDeploymentRequest: AppDefinition = {}, Resource = {}, Deployment Properties = {}",
			request.getDefinition(), request.getResource(), request.getDeploymentProperties());
		String applicationName = applicationName(request);

		getStatus(applicationName)
			.doOnNext(status -> assertApplicationDoesNotExist(applicationName, status))
			// Need to block here to be able to throw exception early
			.block(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()));

		pushApplication(applicationName, request)
			.then(setEnvironmentVariables(applicationName, getEnvironmentVariables(applicationName, request)))
			.then(bindServices(applicationName, request))
			.then(startApplication(applicationName))
			.timeout(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()))
			.doOnSuccess(v -> logger.info("Successfully deployed app {}", applicationName))
			.doOnError(e -> logger.error(String.format("Failed to deploy app %s", applicationName), e))
			.subscribe();

		logger.trace("Exiting deploy().  App {}", applicationName);
		return applicationName;
	}

	@Override
	public AppStatus status(String id) {
		try {
			return getStatus(id)
				.doOnSuccess(v -> logger.info("Successfully computed status [{}] for {}", v, id))
				.doOnError(e -> logger.error(String.format("Failed to compute status for %s", id), e))
				.block(Duration.ofMillis(this.deploymentProperties.getStatusTimeout()));
		}
		catch (Exception timeoutDueToBlock) {
			logger.error("Caught exception while querying for status of {}", id, timeoutDueToBlock);
			return createErrorAppStatus(id);
		}
	}

	@Override
	public void undeploy(String id) {
		requestDeleteApplication(id)
			.timeout(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()))
			.doOnSuccess(v -> logger.info("Successfully undeployed app {}", id))
			.doOnError(e -> logger.error(String.format("Failed to undeploy app %s", id), e))
			.subscribe();
	}

	private void assertApplicationDoesNotExist(String applicationName, AppStatus status) {
		DeploymentState state = status.getState();
		if (state != DeploymentState.unknown && state != DeploymentState.error) {
			throw new IllegalStateException(String.format("App %s is already deployed with state %s", applicationName, state));
		}
	}

	private Mono<Void> bindServices(String applicationName, AppDeploymentRequest request) {
		return Flux.fromIterable(servicesToBind(request))
			.flatMap(service -> requestBindService(applicationName, service)
				.doOnSuccess(v -> logger.debug("Binding service {} to app {}", service, applicationName))
				.doOnError(e -> logger.error(String.format("Failed to bind service %s to app %s", service, applicationName), e)))
			.then();
	}

	private AppStatus createAppStatus(ApplicationDetail applicationDetail, String applicationName) {
		logger.trace("Gathering instances for " + applicationDetail);
		logger.trace("InstanceDetails: " + applicationDetail.getInstanceDetails());

		AppStatus.Builder builder = AppStatus.of(applicationName);

		int i = 0;
		for (InstanceDetail instanceDetail : applicationDetail.getInstanceDetails()) {
			builder.with(new CloudFoundryAppInstanceStatus(applicationDetail, instanceDetail, i++));
		}
		for (; i < applicationDetail.getInstances(); i++) {
			builder.with(new CloudFoundryAppInstanceStatus(applicationDetail, null, i));
		}

		return builder.build();
	}

	private AppStatus createEmptyAppStatus(String applicationName) {
		return AppStatus.of(applicationName)
			.build();
	}

	private AppStatus createErrorAppStatus(String applicationName) {
		return AppStatus.of(applicationName)
			.generalState(DeploymentState.error)
			.build();
	}

	private String applicationName(AppDeploymentRequest request) {
		String prefix = Optional.ofNullable(request.getDeploymentProperties().get(GROUP_PROPERTY_KEY))
			.map(group -> String.format("%s-", group))
			.orElse("");

		String appName = String.format("%s%s", prefix, request.getDefinition().getName());

		return this.applicationNameGenerator.generateAppName(appName);
	}

	private String domain(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(DOMAIN_PROPERTY))
			.orElse(this.deploymentProperties.getDomain());
	}

	private Path getApplication(AppDeploymentRequest request) {
		try {
			return request.getResource().getFile().toPath();
		} catch (IOException e) {
			throw Exceptions.propagate(e);
		}
	}

	private Mono<String> getApplicationId(String applicationName) {
		return requestGetApplication(applicationName)
			.map(ApplicationDetail::getId);
	}

	private Map<String, String> getApplicationProperties(String applicationName, AppDeploymentRequest request) {
		Map<String, String> applicationProperties = getSanitizedApplicationProperties(applicationName, request);

		if (!useSpringApplicationJson(request)) {
			return applicationProperties;
		}

		try {
			return Collections.singletonMap("SPRING_APPLICATION_JSON", OBJECT_MAPPER.writeValueAsString(applicationProperties));
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	private Map<String, String> getCommandLineArguments(AppDeploymentRequest request) {
		if (request.getCommandlineArguments().isEmpty()) {
			return Collections.emptyMap();
		}

		String argumentsAsString = request.getCommandlineArguments().stream()
			.collect(Collectors.joining(" "));
		String yaml = new Yaml().dump(Collections.singletonMap("arguments", argumentsAsString));

		return Collections.singletonMap("JBP_CONFIG_JAVA_MAIN", yaml);
	}

	private Map<String, String> getEnvironmentVariables(String applicationName, AppDeploymentRequest request) {
		Map<String, String> envVariables = new HashMap<>();
		envVariables.putAll(getApplicationProperties(applicationName, request));
		envVariables.putAll(getCommandLineArguments(request));
		String group = request.getDeploymentProperties().get(AppDeployer.GROUP_PROPERTY_KEY);
		if (group != null) {
			envVariables.put("SPRING_CLOUD_APPLICATION_GROUP", group);
		}
		return envVariables;
	}

	private Map<String, String> getSanitizedApplicationProperties(String applicationName, AppDeploymentRequest request) {
		Map<String, String> applicationProperties = new HashMap<>(request.getDefinition().getProperties());

		// Remove server.port as CF assigns a port for us, and we don't want to override that
		Optional.ofNullable(applicationProperties.remove("server.port"))
			.ifPresent(port -> logger.warn("Ignoring 'server.port={}' for app {}, as Cloud Foundry will assign a local dynamic port. Route to the app will use port 80.", port, applicationName));

		return applicationProperties;
	}

	private Mono<AppStatus> getStatus(String applicationName) {
		return requestGetApplication(applicationName)
			.map(applicationDetail -> createAppStatus(applicationDetail, applicationName))
			.otherwise(IllegalArgumentException.class, t -> {
				logger.debug("Application for {} does not exist.", applicationName);
				return Mono.just(createEmptyAppStatus(applicationName));
			})
			.transform(statusRetry(applicationName))
			.otherwiseReturn(createErrorAppStatus(applicationName));
	}

	private ApplicationHealthCheck healthCheck(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(HEALTHCHECK_PROPERTY_KEY))
			.map(this::toApplicationHealthCheck)
			.orElse(this.deploymentProperties.getHealthCheck());
	}

	private String host(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(HOST_PROPERTY))
			.orElse(this.deploymentProperties.getHost());
	}

	private int instances(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(AppDeployer.COUNT_PROPERTY_KEY))
			.map(Integer::parseInt)
			.orElse(this.deploymentProperties.getInstances());
	}

	private Mono<Void> pushApplication(String applicationName, AppDeploymentRequest request) {
		return requestPushApplication(PushApplicationRequest.builder()
			.application(getApplication(request))
			.buildpack(buildpack(request))
			.diskQuota(diskQuota(request))
			.domain(domain(request))
			.healthCheckType(healthCheck(request))
			.host(host(request))
			.instances(instances(request))
			.memory(memory(request))
			.name(applicationName)
			.noRoute(toggleNoRoute(request))
			.noStart(true)
			.routePath(routePath(request))
			.build())
			.doOnSuccess(v -> logger.info("Done uploading bits for {}", applicationName))
			.doOnError(e -> logger.error(String.format("Error creating app %s.  Exception Message %s", applicationName, e.getMessage())));
	}

	private Mono<Void> requestBindService(String applicationName, String service) {
		return this.operations.services()
			.bind(BindServiceInstanceRequest.builder()
				.applicationName(applicationName)
				.serviceInstanceName(service)
				.build())
			.doOnSubscribe(v -> logger.trace("Binding services for app {}, ", applicationName));
	}

	private Mono<Void> requestDeleteApplication(String applicationName) {
		return this.operations.applications()
			.delete(DeleteApplicationRequest.builder()
				.deleteRoutes(true)
				.name(applicationName)
				.build())
			.doOnSubscribe(v -> logger.trace("Deleting app {}", applicationName));
	}

	private Mono<ApplicationDetail> requestGetApplication(String id) {
		return this.operations.applications()
			.get(GetApplicationRequest.builder()
				.name(id)
				.build())
			.doOnSubscribe(v -> logger.trace("Getting Status for app {}", id));
	}

	private Mono<Void> requestPushApplication(PushApplicationRequest request) {
		return this.operations.applications()
			.push(request)
			.doOnSubscribe(v -> logger.trace("Pushing app {}", request.getName()));
	}

	private Mono<Void> requestStartApplication(String applicationName, Duration stagingTimeout, Duration startupTimeout) {
		return this.operations.applications()
			.start(StartApplicationRequest.builder()
				.name(applicationName)
				.stagingTimeout(stagingTimeout)
				.startupTimeout(startupTimeout)
				.build())
			.doOnSubscribe(v -> logger.trace("Starting app {}, ", applicationName));
	}

	private Mono<UpdateApplicationResponse> requestUpdateApplication(String applicationId, Map<String, String> environmentVariables) {
		return this.client.applicationsV2()
			.update(UpdateApplicationRequest.builder()
				.applicationId(applicationId)
				.environmentJsons(environmentVariables)
				.build())
			.doOnSubscribe(v -> logger.trace("Setting environment variables for app {}, ", applicationId));
	}

	private String routePath(AppDeploymentRequest request) {
		return request.getDeploymentProperties().get(ROUTE_PATH_PROPERTY);
	}

	private Mono<UpdateApplicationResponse> setEnvironmentVariables(String applicationName, Map<String, String> environmentVariables) {
		return getApplicationId(applicationName)
			.then(applicationId -> requestUpdateApplication(applicationId, environmentVariables))
			.doOnSuccess(v -> logger.debug("Setting individual env variables to {} for app {}", environmentVariables, applicationName))
			.doOnError(e -> logger.error(String.format("Unable to set individual env variables for app %s", applicationName)));
	}

	private Mono<Void> startApplication(String applicationName) {
		return requestStartApplication(applicationName, this.deploymentProperties.getStagingTimeout(), this.deploymentProperties.getStartupTimeout())
			.doOnSuccess(v -> logger.info("Started app {}", applicationName))
			.doOnError(e -> logger.error(String.format("Failed to start app %s", applicationName), e));
	}

	private ApplicationHealthCheck toApplicationHealthCheck(String raw) {
		try {
			return ApplicationHealthCheck.valueOf(raw.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(String.format("Unsupported health-check value '%s'. Available values are %s", raw,
				StringUtils.arrayToCommaDelimitedString(ApplicationHealthCheck.values())), e);
		}
	}

	private Boolean toggleNoRoute(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(NO_ROUTE_PROPERTY))
			.map(Boolean::valueOf)
			.orElse(null);
	}

	private boolean useSpringApplicationJson(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(USE_SPRING_APPLICATION_JSON_KEY))
			.map(Boolean::valueOf)
			.orElse(this.deploymentProperties.isUseSpringApplicationJson());
	}

}
