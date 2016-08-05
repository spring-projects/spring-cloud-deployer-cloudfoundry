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

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
import org.cloudfoundry.operations.applications.PushApplicationRequest;
import org.cloudfoundry.operations.applications.StartApplicationRequest;
import org.cloudfoundry.operations.services.BindServiceInstanceRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;

import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static java.util.stream.Stream.concat;
import static org.springframework.util.StringUtils.commaDelimitedListToSet;

/**
 * A deployer that targets Cloud Foundry using the public API.
 *
 * @author Eric Bottard
 * @author Greg Turnquist
 */
public class CloudFoundryAppDeployer implements AppDeployer {

	private final CloudFoundryConnectionProperties properties;

	private final CloudFoundryOperations operations;

	private final CloudFoundryClient client;

	private final AppNameGenerator appDeploymentCustomizer;

	private static final Log logger = LogFactory.getLog(CloudFoundryAppDeployer.class);

	public CloudFoundryAppDeployer(CloudFoundryConnectionProperties properties, CloudFoundryOperations operations,
								   CloudFoundryClient client, AppNameGenerator appDeploymentCustomizer) {
		this.properties = properties;
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

		try {
			envVariables.put("SPRING_APPLICATION_JSON",
				new ObjectMapper().writeValueAsString(
					Optional.ofNullable(request.getDefinition().getProperties())
						.orElse(Collections.emptyMap())));
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}

		try {
			return operations.applications()
				.push(PushApplicationRequest.builder()
					.name(name)
					.application(request.getResource().getFile().toPath())
					.domain(properties.getDomain())
					.buildpack(properties.getBuildpack())
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
			.otherwise(e -> emptyAppStatusBuilder(id))
			.map(AppStatus.Builder::build);
	}

	public CloudFoundryConnectionProperties getProperties() {
		return properties;
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
				properties.getServices().stream(),
				commaDelimitedListToSet(request.getDeploymentProperties().get(CloudFoundryConnectionProperties.SERVICES_PROPERTY_KEY)).stream()));
	}

	private int memory(AppDeploymentRequest request) {
		return parseInt(
			request.getDeploymentProperties().getOrDefault(CloudFoundryConnectionProperties.MEMORY_PROPERTY_KEY, valueOf(properties.getMemory())));
	}

	private int instances(AppDeploymentRequest request) {
		return parseInt(
			request.getDeploymentProperties().getOrDefault(AppDeployer.COUNT_PROPERTY_KEY, "1"));
	}

	private int diskQuota(AppDeploymentRequest request) {
		return parseInt(
			request.getDeploymentProperties().getOrDefault(CloudFoundryConnectionProperties.DISK_PROPERTY_KEY, valueOf(properties.getDisk())));
	}

	private Mono<AppStatus.Builder> createAppStatusBuilder(String id, ApplicationDetail ad) {
		return emptyAppStatusBuilder(id)
			.then(b -> addInstances(b, ad));
	}

	private Mono<AppStatus.Builder> emptyAppStatusBuilder(String id) {
		return Mono.just(AppStatus.of(id));
	}

	private Mono<AppStatus.Builder> addInstances(AppStatus.Builder initial, ApplicationDetail ad) {
		return Flux.fromIterable(ad.getInstanceDetails())
			.zipWith(Flux.range(0, ad.getRunningInstances()))
			.reduce(initial, (b, inst) -> b.with(new CloudFoundryAppInstanceStatus(ad, inst.t1, inst.t2)));
	}

}
