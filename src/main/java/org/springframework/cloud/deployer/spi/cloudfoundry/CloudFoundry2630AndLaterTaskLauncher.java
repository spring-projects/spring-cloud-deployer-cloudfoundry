/*
 * Copyright 2016 the original author or authors.
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

package org.springframework.cloud.deployer.spi.cloudfoundry;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.SummaryApplicationResponse;
import org.cloudfoundry.client.v2.applications.UpdateApplicationRequest;
import org.cloudfoundry.client.v2.applications.UpdateApplicationResponse;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.AbstractApplicationSummary;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.ApplicationHealthCheck;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.PushApplicationRequest;
import org.cloudfoundry.operations.applications.StartApplicationRequest;
import org.cloudfoundry.operations.applications.StopApplicationRequest;
import org.cloudfoundry.operations.services.BindServiceInstanceRequest;
import org.cloudfoundry.operations.services.ServiceInstanceSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
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
												CloudFoundryOperations operations) {
		super(client, deploymentProperties);
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
			.doOnError(t -> logger.error(String.format("Task %s launch failed", request.getDefinition().getName()), t))
			.block(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()));
	}

	@Override
	public void destroy(String appName) {
		requestDeleteApplication(appName)
			.timeout(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()))
			.doOnSuccess(v -> logger.info("Successfully destroyed app {}", appName))
			.doOnError(e -> logger.error(String.format("Failed to destroy app %s", appName), e))
			.subscribe();
	}

	private Mono<Void> bindServices(String name, AppDeploymentRequest request) {
		Set<String> servicesToBind = servicesToBind(request);

		return requestListServiceInstances()
			.filter(serviceInstance -> servicesToBind.contains(serviceInstance.getName()))
			.flatMap(serviceInstance -> requestBindService(name, serviceInstance.getName()))
			.then();
	}

	private Mono<AbstractApplicationSummary> deployApplication(AppDeploymentRequest request) {
		String name = request.getDefinition().getName();

		return pushApplication(name, request)
			.then(requestGetApplication(name))
			.then(application -> setEnvironmentVariables(application.getId(), getEnvironmentVariables(request.getDefinition().getProperties()))
				.then(bindServices(name, request))
				.then(startApplication(name))
				.then(stopApplication(name))
				.then(Mono.just(application)));
	}

	private Path getApplication(AppDeploymentRequest request) {
		try {
			return request.getResource().getFile().toPath();
		} catch (IOException e) {
			throw Exceptions.propagate(e);
		}
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
			.otherwiseIfEmpty(deployApplication(request))
			.then(application -> requestGetApplicationSummary(application.getId()));
	}

	private Mono<String> launchTask(SummaryApplicationResponse application, AppDeploymentRequest request) {
		return requestCreateTask(application.getId(), getCommand(application, request), memory(request), request.getDefinition().getName())
			.map(CreateTaskResponse::getId);
	}

	private Mono<Void> pushApplication(String name, AppDeploymentRequest request) {
		return requestPushApplication(PushApplicationRequest.builder()
			.application(getApplication(request))
			.buildpack(buildpack(request))
			.command("/bin/nc -l $PORT")
			.diskQuota(diskQuota(request))
			.healthCheckType(ApplicationHealthCheck.NONE)
			.memory(memory(request))
			.name(name)
			.noRoute(true)
			.noStart(true)
			.build());
	}

	private Mono<Void> requestBindService(String applicationName, String serviceInstanceName) {
		return this.operations.services()
			.bind(BindServiceInstanceRequest.builder()
				.applicationName(applicationName)
				.serviceInstanceName(serviceInstanceName)
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

	private Flux<ServiceInstanceSummary> requestListServiceInstances() {
		return this.operations.services()
			.listInstances();
	}

	private Mono<Void> requestPushApplication(PushApplicationRequest request) {
		return this.operations.applications()
			.push(request);
	}

	private Mono<Void> requestStartApplication(String name, Duration stagingTimeout, Duration startupTimeout) {
		return this.operations.applications()
			.start(StartApplicationRequest.builder()
				.name(name)
				.stagingTimeout(stagingTimeout)
				.startupTimeout(startupTimeout)
				.build());
	}

	private Mono<Void> requestStopApplication(String name) {
		return this.operations.applications()
			.stop(StopApplicationRequest.builder()
				.name(name)
				.build());
	}

	private Mono<Void> requestDeleteApplication(String name) {
		return this.operations.applications()
			.delete(DeleteApplicationRequest.builder()
				.deleteRoutes(true)
				.name(name)
				.build());
	}

	private Mono<UpdateApplicationResponse> requestUpdateApplication(String applicationId, Map<String, String> environmentVariables) {
		return this.client.applicationsV2()
			.update(UpdateApplicationRequest.builder()
				.applicationId(applicationId)
				.environmentJsons(environmentVariables)
				.build());
	}

	private Mono<UpdateApplicationResponse> setEnvironmentVariables(String applicationId, Map<String, String> environmentVariables) {
		return requestUpdateApplication(applicationId, environmentVariables);
	}

	private Mono<Void> startApplication(String name) {
		return requestStartApplication(name, this.deploymentProperties.getStagingTimeout(), this.deploymentProperties.getStartupTimeout());
	}

	private Mono<Void> stopApplication(String name) {
		return requestStopApplication(name);
	}

}
