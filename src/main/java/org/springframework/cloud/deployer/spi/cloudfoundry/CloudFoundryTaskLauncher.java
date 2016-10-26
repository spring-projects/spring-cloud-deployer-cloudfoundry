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

import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.DISK_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.MEMORY_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.CloudFoundryException;
import org.cloudfoundry.client.v3.BuildpackData;
import org.cloudfoundry.client.v3.Lifecycle;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.Type;
import org.cloudfoundry.client.v3.applications.Application;
import org.cloudfoundry.client.v3.applications.CreateApplicationRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationDropletsRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationsRequest;
import org.cloudfoundry.client.v3.droplets.Droplet;
import org.cloudfoundry.client.v3.droplets.DropletResource;
import org.cloudfoundry.client.v3.droplets.GetDropletRequest;
import org.cloudfoundry.client.v3.droplets.GetDropletResponse;
import org.cloudfoundry.client.v3.droplets.StagedResult;
import org.cloudfoundry.client.v3.packages.CreatePackageRequest;
import org.cloudfoundry.client.v3.packages.CreatePackageResponse;
import org.cloudfoundry.client.v3.packages.GetPackageRequest;
import org.cloudfoundry.client.v3.packages.GetPackageResponse;
import org.cloudfoundry.client.v3.packages.Package;
import org.cloudfoundry.client.v3.packages.PackageType;
import org.cloudfoundry.client.v3.packages.StagePackageRequest;
import org.cloudfoundry.client.v3.packages.StagePackageResponse;
import org.cloudfoundry.client.v3.packages.State;
import org.cloudfoundry.client.v3.packages.UploadPackageRequest;
import org.cloudfoundry.client.v3.packages.UploadPackageResponse;
import org.cloudfoundry.client.v3.servicebindings.CreateServiceBindingRequest;
import org.cloudfoundry.client.v3.servicebindings.CreateServiceBindingResponse;
import org.cloudfoundry.client.v3.servicebindings.Relationships;
import org.cloudfoundry.client.v3.servicebindings.ServiceBindingType;
import org.cloudfoundry.client.v3.tasks.CancelTaskRequest;
import org.cloudfoundry.client.v3.tasks.CancelTaskResponse;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskResponse;
import org.cloudfoundry.client.v3.tasks.GetTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.services.ServiceInstance;
import org.cloudfoundry.operations.spaces.GetSpaceRequest;
import org.cloudfoundry.operations.spaces.SpaceDetail;
import org.cloudfoundry.util.DelayUtils;
import org.cloudfoundry.util.PaginationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import org.springframework.util.StringUtils;

/**
 * {@link TaskLauncher} implementation for CloudFoundry.  When a task is launched, if it has not previously been
 * deployed, the app is created, the package is uploaded, and the droplet is created before launching the actual
 * task.  If the app has been deployed previously, the app/package/droplet is reused and a new task is created.
 *
 * @author Greg Turnquist
 * @author Michael Minella
 * @author Ben Hale
 */
public class CloudFoundryTaskLauncher implements TaskLauncher {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryTaskLauncher.class);

	private final CloudFoundryClient client;

	private final CloudFoundryDeploymentProperties deploymentProperties;

	private final CloudFoundryOperations operations;

	private final String space;

	public CloudFoundryTaskLauncher(CloudFoundryClient client,
									CloudFoundryDeploymentProperties deploymentProperties,
									CloudFoundryOperations operations,
									String space) {
		this.client = client;
		this.deploymentProperties = deploymentProperties;
		this.operations = operations;
		this.space = space;
	}

	/**
	 * Setup a reactor flow to cancel a running task.  This implementation opts to be asynchronous.
	 *
	 * @param id the task's id to be canceled as returned from the {@link TaskLauncher#launch(AppDeploymentRequest)}
	 */
	@Override
	public void cancel(String id) {
		requestCancelTask(id)
			.timeout(Duration.ofSeconds(this.deploymentProperties.getTaskTimeout()))
			.doOnSuccess(r -> logger.info("Task {} cancellation successful", id))
			.doOnError(t -> logger.error(String.format("Task %s cancellation failed", id), t))
			.subscribe();
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
			.then(application -> launchTask(application.getId(), request))
			.doOnSuccess(r -> logger.info("Task {} launch successful", request))
			.doOnError(t -> logger.error(String.format("Task %s launch failed", request), t))
			.block(Duration.ofSeconds(this.deploymentProperties.getTaskTimeout()));
	}

	/**
	 * Lookup the current status based on task id.
	 *
	 * @param id taskId as returned from the {@link TaskLauncher#launch(AppDeploymentRequest)}
	 * @return the current task status
	 */
	@Override
	public TaskStatus status(String id) {
		BiFunction<Throwable, String, Mono<TaskStatus>> recoverFrom404 = this::toTaskStatus;
		return requestGetTask(id)
			.map(this::toTaskStatus)
			.otherwise(e -> recoverFrom404.apply(e, id))
			.doOnSuccess(r -> logger.info("Task {} status successful", id))
			.doOnError(t -> logger.error(String.format("Task %s status failed", id), t))
			.block(Duration.ofSeconds(this.deploymentProperties.getTaskTimeout()));
	}

	private Mono<Void> bindServices(AppDeploymentRequest request, Application application) {
		Set<String> servicesToBind = getServicesToBind(request);

		return requestListServiceInstances()
			.filter(instance -> servicesToBind.contains(instance.getName()))
			.map(ServiceInstance::getId)
			.flatMap(serviceInstanceId -> requestCreateServiceBinding(application.getId(), serviceInstanceId))
			.then();
	}

	private Mono<Application> createApplication(AppDeploymentRequest request, String spaceId) {
		AppDefinition definition = request.getDefinition();
		return requestCreateApplication(this.deploymentProperties.getBuildpack(), getEnvironmentVariables(definition.getProperties()), definition.getName(), spaceId);
	}

	private Mono<String> createDroplet(String packageId, AppDeploymentRequest request) {
		return requestStagePackage(getDisk(request), getMemory(request), packageId)
			.map(Droplet::getId);
	}

	private Mono<String> createPackage(String applicationId) {
		return requestCreatePackage(applicationId)
			.map(Package::getId);
	}

	private Mono<String> createTask(String applicationId, Droplet droplet, AppDeploymentRequest request) {
		return requestCreateTask(applicationId, getCommand(droplet, request), droplet.getId(), this.deploymentProperties.getMemory(), request.getDefinition().getName())
			.map(CreateTaskResponse::getId);
	}

	private Mono<Application> deployApplication(AppDeploymentRequest request) {
		return getSpaceId()
			.then(spaceId -> createApplication(request, spaceId))
			.then(application -> uploadPackage(request, application.getId())
				.then(bindServices(request, application))
				.then(Mono.just(application)));
	}

	private InputStream getBits(AppDeploymentRequest request) {
		try {
			return request.getResource().getInputStream();
		} catch (IOException e) {
			throw Exceptions.propagate(e);
		}
	}

	private String getCommand(Droplet droplet, AppDeploymentRequest request) {
		String defaultCommand = ((StagedResult) droplet.getResult()).getProcessTypes().get("web");
		String command = Stream.concat(Stream.of(defaultCommand), request.getCommandlineArguments().stream())
				.collect(Collectors.joining(" "));
		return command;
	}

	private int getDisk(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(DISK_PROPERTY_KEY))
			.map(Integer::parseInt)
			.orElse(this.deploymentProperties.getDisk());
	}

	private Mono<GetDropletResponse> getDroplet(String applicationId) {
		return requestListDroplets(applicationId)
				.single()
				.map(DropletResource::getId)
				// LISTing then GETting is currently necessary, as the listing redacts some information in its results
				.then(this::requestGetDroplet);
	}

	private Map<String, String> getEnvironmentVariables(Map<String, String> properties) {
		try {
			return Collections.singletonMap("SPRING_APPLICATION_JSON", OBJECT_MAPPER.writeValueAsString(properties));
		} catch (JsonProcessingException e) {
			throw Exceptions.propagate(e);
		}
	}

	private int getMemory(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(MEMORY_PROPERTY_KEY))
			.map(Integer::parseInt)
			.orElse(this.deploymentProperties.getMemory());
	}

	private Mono<Application> getOptionalApplication(String name) {
		return requestListApplications(name)
			.singleOrEmpty();
	}

	private Mono<Application> getOrDeployApplication(AppDeploymentRequest request) {
		return getOptionalApplication(request.getDefinition().getName())
			.otherwiseIfEmpty(deployApplication(request));
	}

	private Set<String> getServicesToBind(AppDeploymentRequest request) {
		Set<String> services = new HashSet<>();
		services.addAll(this.deploymentProperties.getServices());
		services.addAll(StringUtils.commaDelimitedListToSet(request.getDeploymentProperties().get(SERVICES_PROPERTY_KEY)));
		return services;
	}

	private Mono<String> getSpaceId() {
		return requestSpace(this.space)
			.map(SpaceDetail::getId);
	}

	private Mono<String> launchTask(String applicationId, AppDeploymentRequest request) {
		return getDroplet(applicationId)
			.then(droplet -> createTask(applicationId, droplet, request));
	}

	private Mono<CancelTaskResponse> requestCancelTask(String taskId) {
		return this.client.tasks()
			.cancel(CancelTaskRequest.builder()
				.taskId(taskId)
				.build());
	}

	private Mono<Application> requestCreateApplication(String buildpack, Map<String, String> environmentVariables, String name, String spaceId) {
		return this.client.applicationsV3()
			.create(CreateApplicationRequest.builder()
				.environmentVariables(environmentVariables)
				.lifecycle(Lifecycle.builder()
					.type(Type.BUILDPACK)
					.data(BuildpackData.builder()
						.buildpack(buildpack)
						.build())
					.build())
				.name(name)
				.relationships(org.cloudfoundry.client.v3.applications.Relationships.builder()
					.space(Relationship.builder()
						.id(spaceId)
						.build())
					.build())
				.build())
			.cast(Application.class);
	}

	private Mono<CreatePackageResponse> requestCreatePackage(String applicationId) {
		return this.client.packages()
			.create(CreatePackageRequest.builder()
				.applicationId(applicationId)
				.type(PackageType.BITS)
				.build());
	}

	private Mono<CreateServiceBindingResponse> requestCreateServiceBinding(String applicationId, String serviceInstanceId) {
		return this.client.serviceBindingsV3()
			.create(CreateServiceBindingRequest.builder()
				.relationships(Relationships.builder()
					.application(Relationship.builder()
						.id(applicationId)
						.build())
					.serviceInstance(Relationship.builder()
						.id(serviceInstanceId)
						.build())
					.build())
				.type(ServiceBindingType.APPLICATION)
				.build());
	}

	private Mono<CreateTaskResponse> requestCreateTask(String applicationId, String command, String dropletId, int memory, String name) {
		return this.client.tasks()
			.create(CreateTaskRequest.builder()
				.applicationId(applicationId)
				.command(command)
				.dropletId(dropletId)
				.memoryInMb(memory)
				.name(name)
				.build());
	}

	private Mono<GetDropletResponse> requestGetDroplet(String dropletId) {
		return this.client.droplets()
			.get(GetDropletRequest.builder()
				.dropletId(dropletId)
				.build());
	}

	private Mono<GetPackageResponse> requestGetPackage(String packageId) {
		return this.client.packages()
			.get(GetPackageRequest.builder()
				.packageId(packageId)
				.build());
	}

	private Mono<GetTaskResponse> requestGetTask(String taskId) {
		return this.client.tasks()
			.get(GetTaskRequest.builder()
				.taskId(taskId)
				.build());
	}

	private Flux<Application> requestListApplications(String name) {
		return PaginationUtils
			.requestClientV3Resources(page -> this.client.applicationsV3()
				.list(ListApplicationsRequest.builder()
					.name(name)
					.page(page)
					.build()))
			.cast(Application.class);
	}

	private Flux<DropletResource> requestListDroplets(String applicationId) {
		return PaginationUtils
			.requestClientV3Resources(page -> this.client.applicationsV3()
				.listDroplets(ListApplicationDropletsRequest.builder()
					.applicationId(applicationId)
					.page(page)
					.build()));
	}

	private Flux<ServiceInstance> requestListServiceInstances() {
		return this.operations.services()
			.listInstances();
	}

	private Mono<SpaceDetail> requestSpace(String space) {
		return this.operations.spaces()
			.get(GetSpaceRequest.builder()
				.name(space)
				.build());
	}

	private Mono<StagePackageResponse> requestStagePackage(int disk, int memory, String packageId) {
		return this.client.packages()
			.stage(StagePackageRequest.builder()
				.packageId(packageId)
				.stagingDiskInMb(disk)
				.stagingMemoryInMb(memory)
				.build());
	}

	private Mono<UploadPackageResponse> requestUploadPackage(InputStream bits, String packageId) {
		return this.client.packages()
			.upload(UploadPackageRequest.builder()
				.bits(bits)
				.packageId(packageId)
				.build());
	}

	private Mono<TaskStatus> toTaskStatus(Throwable throwable, String id) {
		if ((throwable instanceof CloudFoundryException)
			&& ((CloudFoundryException) throwable).getCode() == 10010) {
			return Mono.just(new TaskStatus(id, LaunchState.unknown, null));
		}
		else {
			return Mono.error(throwable);
		}
	}

	private TaskStatus toTaskStatus(GetTaskResponse response) {
		switch (response.getState()) {
			case SUCCEEDED_STATE:
				return new TaskStatus(response.getId(), LaunchState.complete, null);
			case RUNNING_STATE:
				return new TaskStatus(response.getId(), LaunchState.running, null);
			case PENDING_STATE:
				return new TaskStatus(response.getId(), LaunchState.launching, null);
			case CANCELING_STATE:
				return new TaskStatus(response.getId(), LaunchState.cancelled, null);
			case FAILED_STATE:
				return new TaskStatus(response.getId(), LaunchState.failed, null);
			default:
				throw new IllegalStateException(String.format("Unsupported CF task state %s", response.getState()));
		}
	}

	private Mono<GetDropletResponse> uploadPackage(AppDeploymentRequest request, String applicationId) {
		return createPackage(applicationId)
			.then(packageId -> requestUploadPackage(getBits(request), packageId)
				.then(waitForPackageProcessing(packageId))
				.then(createDroplet(packageId, request)))
			.then(this::waitForDropletProcessing);
	}

	private Mono<GetDropletResponse> waitForDropletProcessing(String dropletId) {
		return requestGetDroplet(dropletId)
			.filter(response -> !response.getState().equals(org.cloudfoundry.client.v3.droplets.State.PENDING))
			.repeatWhenEmpty(50, DelayUtils.exponentialBackOff(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(10)));
	}

	private Mono<GetPackageResponse> waitForPackageProcessing(String packageId) {
		return requestGetPackage(packageId)
			.filter(response -> response.getState().equals(State.READY))
			.repeatWhenEmpty(50, DelayUtils.exponentialBackOff(Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofMinutes(10)));
	}

}
