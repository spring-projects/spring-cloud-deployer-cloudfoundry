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

import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static java.util.stream.Stream.concat;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.DISK_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.MEMORY_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY;
import static org.springframework.util.StringUtils.commaDelimitedListToSet;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.UpdateApplicationRequest;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.InstanceDetail;
import org.cloudfoundry.operations.applications.PushApplicationRequest;
import org.cloudfoundry.operations.applications.StartApplicationRequest;
import org.cloudfoundry.operations.services.BindServiceInstanceRequest;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;

/**
 * A deployer that targets Cloud Foundry using the public API.
 *
 * @author Eric Bottard
 * @author Greg Turnquist
 */
public class CloudFoundryAppDeployer implements AppDeployer {

	private final CloudFoundryConnectionProperties connectionProperties;

	private final CloudFoundryDeploymentProperties deploymentProperties;

	private final CloudFoundryOperations operations;

	private final CloudFoundryClient client;

	private final AppNameGenerator appDeploymentCustomizer;

	private static final Log logger = LogFactory.getLog(CloudFoundryAppDeployer.class);

	public CloudFoundryAppDeployer(CloudFoundryConnectionProperties connectionProperties,
			CloudFoundryDeploymentProperties deploymentProperties,
			CloudFoundryOperations operations,
			CloudFoundryClient client,
			AppNameGenerator appDeploymentCustomizer) {
		this.connectionProperties = connectionProperties;
		this.deploymentProperties = deploymentProperties;
		this.operations = operations;
		this.client = client;
		this.appDeploymentCustomizer = appDeploymentCustomizer;
	}

	@Override
	public String deploy(AppDeploymentRequest request) {
		String deploymentId = deploymentId(request);
		DeploymentState state = status(deploymentId).getState();
		if (state != DeploymentState.unknown) {
			throw new IllegalStateException(String.format("App %s is already deployed with state %s",
				deploymentId, state));
		}

		asyncDeploy(request)
			.subscribe();

		return deploymentId;
	}

	Mono<Void> asyncDeploy(AppDeploymentRequest request) {
		String name = deploymentId(request);

		Map<String, String> envVariables = new HashMap<>();

		if (useSpringApplicationJson(request)) {
			try {
				envVariables.put("SPRING_APPLICATION_JSON",
						new ObjectMapper().writeValueAsString(
								Optional.ofNullable(request.getDefinition().getProperties())
										.orElse(Collections.emptyMap())));
			} catch (JsonProcessingException e) {
				throw new RuntimeException(e);
			}
		} else {
			envVariables.putAll(
					Optional.ofNullable(request.getDefinition().getProperties())
							.orElse(Collections.emptyMap()));
		}

		if (request.getCommandlineArguments() != null && !request.getCommandlineArguments().isEmpty()) {
			Yaml yaml = new Yaml();
			String argsAsYaml = yaml.dump(Collections.singletonMap("arguments",
					request.getCommandlineArguments().stream().collect(Collectors.joining(" "))));
			envVariables.put("JBP_CONFIG_JAVA_MAIN", argsAsYaml);
		}

		try {
			return operations.applications()
				.push(PushApplicationRequest.builder()
					.name(name)
					.application(request.getResource().getFile().toPath())
					.domain(connectionProperties.getDomain())
					.buildpack(buildpack(request))
					.diskQuota(diskQuota(request))
					.instances(instances(request))
					.memory(memory(request))
					.noStart(true)
					.build())
				.doOnSuccess(v -> logger.info(String.format("Done uploading bits for %s", name)))
				.doOnError(e -> logger.error(String.format("Error creating app %s", name), e))
				// TODO: GH-34: Replace the following clause with an -operations API call
				.then(() -> getApplicationId(name)
					.then(applicationId -> client.applicationsV2()
						.update(UpdateApplicationRequest.builder()
							.applicationId(applicationId)
							.environmentJsons(envVariables)
							.build()))
					.doOnSuccess(v -> logger.debug(String.format("Setting individual env variables to %s for app %s", envVariables, name)))
					.doOnError(e -> logger.error(String.format("Unable to set individual env variables for app %s", name)))
				)
				.then(() -> servicesToBind(request)
					.flatMap(service -> operations.services()
						.bind(BindServiceInstanceRequest.builder()
							.applicationName(name)
							.serviceInstanceName(service)
							.build())
						.doOnSuccess(v -> {
							logger.debug(String.format("Binding service %s to app %s", service, name));
						})
						.doOnError(e -> logger.error(String.format("Failed to bind service %s to app %s", service, name), e))
					)
					.then() /* this then() merges all the bindServices Mono<Void>'s into 1 */)
				.then(() -> operations.applications()
					.start(StartApplicationRequest.builder()
						.name(name)
						.build())
					.doOnSuccess(v -> logger.info(String.format("Started app %s", name)))
					.doOnError(e -> logger.error(String.format("Failed to start app %s", name), e))
				);
		} catch (IOException e) {
			return Mono.error(e);
		}
	}

	@Override
	public void undeploy(String id) {
		asyncUndeploy(id).subscribe();
	}

	Mono<Void> asyncUndeploy(String id) {
		return operations.applications()
			.delete(
				DeleteApplicationRequest.builder()
					.deleteRoutes(true)
					.name(id)
					.build()
			)
			.doOnSuccess(v -> logger.info(String.format("Sucessfully undeployed app %s", id)))
			.doOnError(e -> logger.error(String.format("Failed to undeploy app %s", id), e));
	}

	@Override
	public AppStatus status(String id) {
		return asyncStatus(id)
			.block();
	}

	Mono<AppStatus> asyncStatus(String id) {
		return operations.applications()
			.get(GetApplicationRequest.builder()
				.name(id)
				.build())
			.then(ad -> createAppStatusBuilder(id, ad))
			.otherwise(e ->  emptyAppStatusBuilder(id))
			.map(AppStatus.Builder::build)
			.doOnSuccess(v -> logger.info(String.format("Successfully computed status [%s] for %s", v, id)))
			.doOnError(e -> logger.error(String.format("Failed to compute status for %s", id), e));
	}

	private String deploymentId(AppDeploymentRequest request) {
		String appName = Optional.ofNullable(request.getDeploymentProperties().get(GROUP_PROPERTY_KEY))
			.map(groupName -> String.format("%s-", groupName))
			.orElse("") + request.getDefinition().getName();
		return appDeploymentCustomizer.generateAppName(appName);
	}

	private Mono<String> getApplicationId(String name) {
		return operations.applications()
			.get(GetApplicationRequest.builder()
				.name(name)
				.build())
			.map(applicationDetail -> applicationDetail.getId());
	}

	private Flux<String> servicesToBind(AppDeploymentRequest request) {
		return Flux.fromStream(
			concat(
				deploymentProperties.getServices().stream(),
				commaDelimitedListToSet(request.getDeploymentProperties().get(SERVICES_PROPERTY_KEY)).stream()));
	}

	private int memory(AppDeploymentRequest request) {
		return parseInt(
			request.getDeploymentProperties().getOrDefault(MEMORY_PROPERTY_KEY, valueOf(deploymentProperties.getMemory())));
	}

	private int instances(AppDeploymentRequest request) {
		return parseInt(
			request.getDeploymentProperties().getOrDefault(AppDeployer.COUNT_PROPERTY_KEY, "1"));
	}

	private String buildpack(AppDeploymentRequest request) {
		return request.getDeploymentProperties().getOrDefault(CloudFoundryDeploymentProperties.BUILDPACK_PROPERTY_KEY, deploymentProperties.getBuildpack());
	}

	private int diskQuota(AppDeploymentRequest request) {
		return parseInt(
			request.getDeploymentProperties().getOrDefault(DISK_PROPERTY_KEY, valueOf(deploymentProperties.getDisk())));
	}

	private boolean useSpringApplicationJson(AppDeploymentRequest request) {
		return Boolean.valueOf(
				request.getDeploymentProperties().getOrDefault(CloudFoundryDeploymentProperties.USE_SPRING_APPLICATION_JSON_KEY, valueOf(deploymentProperties.isUseSpringApplicationJson())));
	}

	private Mono<AppStatus.Builder> createAppStatusBuilder(String id, ApplicationDetail ad) {
		return emptyAppStatusBuilder(id)
			.then(b -> addInstances(b, ad));
	}

	private Mono<AppStatus.Builder> emptyAppStatusBuilder(String id) {
		return Mono.just(AppStatus.of(id));
	}

	private Mono<AppStatus.Builder> addInstances(AppStatus.Builder initial, ApplicationDetail ad) {
		logger.trace("Gathering instances for " + ad);
		logger.trace("InstanceDetails: " + ad.getInstanceDetails());

		int i = 0;
		for (InstanceDetail instanceDetail : ad.getInstanceDetails()) {
			initial.with(new CloudFoundryAppInstanceStatus(ad, instanceDetail, i++));
		}
		for (; i < ad.getInstances() ; i++) {
			initial.with(new CloudFoundryAppInstanceStatus(ad, null, i));
		}
		return Mono.just(initial);
	}

}
