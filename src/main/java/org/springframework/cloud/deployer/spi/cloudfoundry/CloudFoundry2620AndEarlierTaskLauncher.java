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
import org.cloudfoundry.client.v3.BuildpackData;
import org.cloudfoundry.client.v3.Lifecycle;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.Type;
import org.cloudfoundry.client.v3.applications.Application;
import org.cloudfoundry.client.v3.applications.CreateApplicationRequest;
import org.cloudfoundry.client.v3.applications.DeleteApplicationRequest;
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
import org.cloudfoundry.client.v3.servicebindings.DeleteServiceBindingRequest;
import org.cloudfoundry.client.v3.servicebindings.ListServiceBindingsRequest;
import org.cloudfoundry.client.v3.servicebindings.Relationships;
import org.cloudfoundry.client.v3.servicebindings.ServiceBindingResource;
import org.cloudfoundry.client.v3.servicebindings.ServiceBindingType;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.services.ServiceInstanceSummary;
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
public class CloudFoundry2620AndEarlierTaskLauncher extends AbstractCloudFoundryTaskLauncher {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundry2630AndLaterTaskLauncher.class);

	private final CloudFoundryClient client;

	private final CloudFoundryDeploymentProperties deploymentProperties;

	private final CloudFoundryOperations operations;

	private final String space;

	public CloudFoundry2620AndEarlierTaskLauncher(CloudFoundryClient client,
												  CloudFoundryDeploymentProperties deploymentProperties,
												  CloudFoundryOperations operations,
												  String space) {
		super(client, deploymentProperties);
		this.client = client;
		this.deploymentProperties = deploymentProperties;
		this.operations = operations;
		this.space = space;
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
			.doOnSuccess(r -> logger.info("Task {} launch successful", request.getDefinition().getName()))
			.doOnError(t -> logger.error(String.format("Task %s launch failed", request.getDefinition().getName()), t))
			.block(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()));
	}

	@Override
	public void destroy(String appName) {
		getOptionalApplication(appName)
			.doOnSuccess(a -> {if(a == null) logger.info("Did not destroy app {} as it did not exist", appName);})
			.then(app -> requestListServiceBindings(app.getId())
				.flatMap(sb -> requestDeleteServiceBinding(sb.getId()))
				.then(requestDeleteApplication(app.getId()))
				.doOnSuccess(v -> logger.info("Successfully destroyed app {}", appName))
				.doOnError(e -> logger.error(String.format("Failed to destroy app %s", appName), e))
			)
			.timeout(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()))
			.subscribe();
	}

	private Mono<Void> requestDeleteServiceBinding(String sbId) {
		return this.client.serviceBindingsV3().delete(DeleteServiceBindingRequest.builder()
			.serviceBindingId(sbId)
			.build());
	}

	private Flux<ServiceBindingResource> requestListServiceBindings(String appId) {
		return PaginationUtils.requestClientV3Resources(page -> this.client.serviceBindingsV3().list(
			ListServiceBindingsRequest.builder()
				.applicationId(appId)
				.page(page)
				.build())
		);
	}

	private Mono<Void> bindServices(AppDeploymentRequest request, Application application) {
		Set<String> servicesToBind = servicesToBind(request);

		return requestListServiceInstances()
			.filter(instance -> servicesToBind.contains(instance.getName()))
			.map(ServiceInstanceSummary::getId)
			.flatMap(serviceInstanceId -> requestCreateServiceBinding(application.getId(), serviceInstanceId))
			.then();
	}

	private Mono<Application> createApplication(AppDeploymentRequest request, String spaceId) {
		AppDefinition definition = request.getDefinition();
		return requestCreateApplication(buildpack(request), getEnvironmentVariables(definition.getProperties()), definition.getName(), spaceId);
	}

	private Mono<String> createDroplet(String packageId, AppDeploymentRequest request) {
		return requestStagePackage(diskQuota(request), memory(request), packageId)
			.map(Droplet::getId);
	}

	private Mono<String> createPackage(String applicationId) {
		return requestCreatePackage(applicationId)
			.map(Package::getId);
	}

	private Mono<String> createTask(String applicationId, Droplet droplet, AppDeploymentRequest request) {
		return requestCreateTask(applicationId, getCommand(droplet, request), droplet.getId(), memory(request), request.getDefinition().getName())
			.map(CreateTaskResponse::getId);
	}

	private Mono<Application> deployApplication(AppDeploymentRequest request) {
		return getSpaceId()
			.then(spaceId -> createApplication(request, spaceId))
			.then(application -> uploadPackage(request, application.getId())
				.then(bindServices(request, application))
				.then(Mono.just(application)));
	}

	private Path getBits(AppDeploymentRequest request) {
		try {
			return request.getResource().getFile().toPath();
		} catch (IOException e) {
			throw Exceptions.propagate(e);
		}
	}

	private String getCommand(Droplet droplet, AppDeploymentRequest request) {
		String defaultCommand = ((StagedResult) droplet.getResult()).getProcessTypes().get("web");
		return Stream.concat(Stream.of(defaultCommand), request.getCommandlineArguments().stream())
			.collect(Collectors.joining(" "));
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

	private Mono<Application> getOptionalApplication(String name) {
		return requestListApplications(name)
			.singleOrEmpty();
	}

	private Mono<Application> getOrDeployApplication(AppDeploymentRequest request) {
		return getOptionalApplication(request.getDefinition().getName())
			.otherwiseIfEmpty(deployApplication(request));
	}

	private Mono<String> getSpaceId() {
		return requestSpace(this.space)
			.map(SpaceDetail::getId);
	}

	private Mono<String> launchTask(String applicationId, AppDeploymentRequest request) {
		return getDroplet(applicationId)
			.then(droplet -> createTask(applicationId, droplet, request));
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

	private Mono<Void> requestDeleteApplication(String appId) {
		return this.client.applicationsV3()
			.delete(DeleteApplicationRequest.builder()
				.applicationId(appId)
				.build());
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

	private Flux<ServiceInstanceSummary> requestListServiceInstances() {
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

	private Mono<UploadPackageResponse> requestUploadPackage(Path bits, String packageId) {
		return this.client.packages()
			.upload(UploadPackageRequest.builder()
				.bits(bits)
				.packageId(packageId)
				.build());
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
