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
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.SummaryApplicationResponse;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.AbstractApplicationSummary;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.ApplicationHealthCheck;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.Docker;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.PushApplicationManifestRequest;
import org.cloudfoundry.operations.applications.StopApplicationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;

/**
 * {@link TaskLauncher} implementation for CloudFoundry.  When a task is launched, if it has not previously been
 * deployed, the app is created, the package is uploaded, and the droplet is created before launching the actual
 * task.  If the app has been deployed previously, the app/package/droplet is reused and a new task is created.
 *
 * @author Greg Turnquist
 * @author Michael Minella
 * @author Ben Hale
 */
public class CloudFoundry2630AndLaterTaskLauncher extends AbstractCloudFoundryTaskLauncher {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundry2630AndLaterTaskLauncher.class);

	private final CloudFoundryClient client;

	private final CloudFoundryDeploymentProperties deploymentProperties;

	private final CloudFoundryOperations operations;

	public CloudFoundry2630AndLaterTaskLauncher(CloudFoundryClient client,
												CloudFoundryDeploymentProperties deploymentProperties,
												CloudFoundryOperations operations,
											    RuntimeEnvironmentInfo runtimeEnvironmentInfo) {
		super(client, deploymentProperties, runtimeEnvironmentInfo);
		this.client = client;
		this.deploymentProperties = deploymentProperties;
		this.operations = operations;
	}

	/**
	 * Set up a reactor flow to launch a task. Before launch, check if the base application exists. If not, deploy then launch task.
	 *
	 * @param request description of the application to be launched
	 * @return name of the launched task, returned without waiting for reactor pipeline to complete
	 */
	@Override
	public String launch(AppDeploymentRequest request) {
		return getOrDeployApplication(request)
			.then(application -> launchTask(application, request))
			.doOnSuccess(r -> logger.info("Task {} launch successful", request.getDefinition().getName()))
			.doOnError(logError(String.format("Task %s launch failed", request.getDefinition().getName())))
			.block(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()));
	}

	@Override
	public void destroy(String appName) {
		requestDeleteApplication(appName)
			.timeout(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()))
			.doOnSuccess(v -> logger.info("Successfully destroyed app {}", appName))
			.doOnError(logError(String.format("Failed to destroy app %s", appName)))
			.subscribe();
	}

	private Mono<AbstractApplicationSummary> deployApplication(AppDeploymentRequest request) {
		String name = request.getDefinition().getName();

		return pushApplication(name, request)
			.then(requestStopApplication(name))
			.then(requestGetApplication(name))
			.cast(AbstractApplicationSummary.class);
	}

	private String getCommand(SummaryApplicationResponse application, AppDeploymentRequest request) {
		return Stream.concat(Stream.of(application.getDetectedStartCommand()), request.getCommandlineArguments().stream())
			.collect(Collectors.joining(" "));
	}

	private Map<String, String> getEnvironmentVariables(Map<String, String> properties) {
		try {
			return Collections.singletonMap("SPRING_APPLICATION_JSON", OBJECT_MAPPER.writeValueAsString(properties));
		} catch (JsonProcessingException e) {
			throw Exceptions.propagate(e);
		}
	}

	private Mono<AbstractApplicationSummary> getOptionalApplication(AppDeploymentRequest request) {
		String name = request.getDefinition().getName();

		return requestListApplications()
			.filter(application -> name.equals(application.getName()))
			.singleOrEmpty()
			.cast(AbstractApplicationSummary.class);
	}

	private Mono<SummaryApplicationResponse> getOrDeployApplication(AppDeploymentRequest request) {
		return getOptionalApplication(request)
			.switchIfEmpty(deployApplication(request))
			.then(application -> requestGetApplicationSummary(application.getId()));
	}

	private Mono<String> launchTask(SummaryApplicationResponse application, AppDeploymentRequest request) {
		return requestCreateTask(application.getId(), getCommand(application, request), memory(request), request.getDefinition().getName())
			.map(CreateTaskResponse::getId);
	}

	private Mono<Void> pushApplication(String name, AppDeploymentRequest request) {
		return requestPushApplication(PushApplicationManifestRequest.builder()
			.manifest(ApplicationManifest.builder()
				.path(getApplication(request))
				.docker(Docker.builder().image(getDockerImage(request)).build())
				.buildpack(buildpack(request))
				.command("echo '*** First run of container to allow droplet creation.***' && sleep 300")
				.disk(diskQuota(request))
				.environmentVariables(getEnvironmentVariables(request.getDefinition().getProperties()))
				.healthCheckType(ApplicationHealthCheck.NONE)
				.memory(memory(request))
				.name(name)
				.noRoute(true)
				.services(servicesToBind(request))
				.build())
			.stagingTimeout(this.deploymentProperties.getStagingTimeout())
			.startupTimeout(this.deploymentProperties.getStartupTimeout())
			.build());
	}

	private Mono<CreateTaskResponse> requestCreateTask(String applicationId, String command, int memory, String name) {
		return this.client.tasks()
			.create(CreateTaskRequest.builder()
				.applicationId(applicationId)
				.command(command)
				.memoryInMb(memory)
				.name(name)
				.build());
	}

	private Mono<Void> requestDeleteApplication(String name) {
		return this.operations.applications()
			.delete(DeleteApplicationRequest.builder()
				.deleteRoutes(deploymentProperties.isDeleteRoutes())
				.name(name)
				.build());
	}

	private Mono<ApplicationDetail> requestGetApplication(String name) {
		return this.operations.applications()
			.get(GetApplicationRequest.builder()
				.name(name)
				.build());
	}

	private Mono<SummaryApplicationResponse> requestGetApplicationSummary(String applicationId) {
		return this.client.applicationsV2()
			.summary(org.cloudfoundry.client.v2.applications.SummaryApplicationRequest.builder()
				.applicationId(applicationId)
				.build());
	}

	private Flux<ApplicationSummary> requestListApplications() {
		return this.operations.applications()
			.list();
	}

	private Mono<Void> requestPushApplication(PushApplicationManifestRequest request) {
		return this.operations.applications()
			.pushManifest(request);
	}

	private Mono<Void> requestStopApplication(String name) {
		return this.operations.applications()
			.stop(StopApplicationRequest.builder()
				.name(name)
				.build());
	}

}
