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

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v3.BuildpackData;
import org.cloudfoundry.client.v3.Lifecycle;
import org.cloudfoundry.client.v3.Pagination;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.Type;
import org.cloudfoundry.client.v3.applications.ApplicationResource;
import org.cloudfoundry.client.v3.applications.ApplicationsV3;
import org.cloudfoundry.client.v3.applications.CreateApplicationRequest;
import org.cloudfoundry.client.v3.applications.CreateApplicationResponse;
import org.cloudfoundry.client.v3.applications.DeleteApplicationRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationDropletsRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationDropletsResponse;
import org.cloudfoundry.client.v3.applications.ListApplicationsRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationsResponse;
import org.cloudfoundry.client.v3.droplets.DropletResource;
import org.cloudfoundry.client.v3.droplets.Droplets;
import org.cloudfoundry.client.v3.droplets.GetDropletRequest;
import org.cloudfoundry.client.v3.droplets.GetDropletResponse;
import org.cloudfoundry.client.v3.droplets.StagedResult;
import org.cloudfoundry.client.v3.packages.CreatePackageRequest;
import org.cloudfoundry.client.v3.packages.CreatePackageResponse;
import org.cloudfoundry.client.v3.packages.GetPackageRequest;
import org.cloudfoundry.client.v3.packages.GetPackageResponse;
import org.cloudfoundry.client.v3.packages.PackageType;
import org.cloudfoundry.client.v3.packages.Packages;
import org.cloudfoundry.client.v3.packages.StagePackageRequest;
import org.cloudfoundry.client.v3.packages.StagePackageResponse;
import org.cloudfoundry.client.v3.packages.State;
import org.cloudfoundry.client.v3.packages.UploadPackageRequest;
import org.cloudfoundry.client.v3.packages.UploadPackageResponse;
import org.cloudfoundry.client.v3.servicebindings.CreateServiceBindingRequest;
import org.cloudfoundry.client.v3.servicebindings.CreateServiceBindingResponse;
import org.cloudfoundry.client.v3.servicebindings.DeleteServiceBindingRequest;
import org.cloudfoundry.client.v3.servicebindings.ListServiceBindingsRequest;
import org.cloudfoundry.client.v3.servicebindings.ListServiceBindingsResponse;
import org.cloudfoundry.client.v3.servicebindings.Relationships;
import org.cloudfoundry.client.v3.servicebindings.ServiceBindingResource;
import org.cloudfoundry.client.v3.servicebindings.ServiceBindingType;
import org.cloudfoundry.client.v3.servicebindings.ServiceBindingsV3;
import org.cloudfoundry.client.v3.tasks.CancelTaskRequest;
import org.cloudfoundry.client.v3.tasks.CancelTaskResponse;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskResponse;
import org.cloudfoundry.client.v3.tasks.GetTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.cloudfoundry.client.v3.tasks.Tasks;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.services.ServiceInstanceSummary;
import org.cloudfoundry.operations.services.ServiceInstanceType;
import org.cloudfoundry.operations.services.Services;
import org.cloudfoundry.operations.spaces.GetSpaceRequest;
import org.cloudfoundry.operations.spaces.SpaceDetail;
import org.cloudfoundry.operations.spaces.Spaces;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import org.springframework.cloud.deployer.spi.util.ByteSizeUtils;
import org.springframework.core.io.Resource;

/**
 * @author Michael Minella
 * @author Ben Hale
 */
public class CloudFoundry2620AndEarlierTaskLauncherTests {

	private final CloudFoundryDeploymentProperties deploymentProperties = new CloudFoundryDeploymentProperties();

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private ApplicationsV3 applicationsV3;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private CloudFoundryClient client;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Droplets droplets;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private File file;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Path path;

	private CloudFoundry2620AndEarlierTaskLauncher launcher;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private CloudFoundryOperations operations;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Packages packages;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Resource resource;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private ServiceBindingsV3 serviceBindingsV3;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Services services;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Spaces spaces;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Tasks tasks;

	@Test
	public void cancel() {
		givenRequestCancelTask("test-task-id", Mono.just(CancelTaskResponse.builder()
			.id("test-task-id")
			.state(org.cloudfoundry.client.v3.tasks.State.CANCELING_STATE)
			.build()));

		this.launcher.cancel("test-task-id");
	}

	@Test
	public void launchTaskApplicationExists() {
		givenRequestListApplications("test-application", Mono.just(ListApplicationsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(1)
				.build())
			.resource(ApplicationResource.builder()
				.id("test-application-id")
				.build())
			.build()));

		givenRequestListDroplets("test-application-id", Mono.just(ListApplicationDropletsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(1)
				.build())
			.resource(DropletResource.builder()
				.result(StagedResult.builder()
					.processType("web", "test-command")
					.build())
				.id("test-droplet-id")
				.build())
			.build()));

		givenRequestGetDroplet("test-droplet-id",
			Mono.just(GetDropletResponse.builder()
				.result(StagedResult.builder()
					.processType("web", "test-command")
					.build())
				.id("test-droplet-id")
				.build()));

		givenRequestCreateTask("test-application-id", "test-command", "test-droplet-id", this.deploymentProperties.getMemory(), "test-application", Mono.just(CreateTaskResponse.builder()
			.id("test-task-id")
			.build()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, this.resource, Collections.emptyMap());

		String taskId = this.launcher.launch(request);

		assertThat(taskId, equalTo("test-task-id"));
	}

	@Test
	public void launchTaskWithNonExistentApplication() {
		givenRequestListApplications("test-application", Mono.just(ListApplicationsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(0)
				.build())
			.resource()
			.build()));

		givenRequestSpace("test-space", Mono.just(SpaceDetail.builder()
			.id("test-space-id")
			.name("test-name")
			.organization("test-organization")
			.build()));

		givenRequestCreateApplication(this.deploymentProperties.getBuildpack(), Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"), "test-application", "test-space-id",
			Mono.just(CreateApplicationResponse.builder()
				.id("test-application-id")
				.build()));

		givenRequestCreatePackage("test-application-id", Mono.just(CreatePackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestUploadPackage(this.path, "test-package-id", Mono.just(UploadPackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestGetPackage("test-package-id", Mono.just(GetPackageResponse.builder()
			.id("test-package-id")
			.state(State.READY)
			.build()));

		givenRequestStagePackage(this.deploymentProperties.getDisk(), this.deploymentProperties.getMemory(), "test-package-id", Mono.just(StagePackageResponse.builder()
			.id("test-droplet-id")
			.build()));

		givenRequestGetDroplet("test-droplet-id",
			Mono.just(GetDropletResponse.builder()
				.id("test-droplet-id")
				.state(org.cloudfoundry.client.v3.droplets.State.STAGED)
				.build()),
			Mono.just(GetDropletResponse.builder()
				.result(StagedResult.builder()
					.processType("web", "test-command")
					.build())
				.id("test-droplet-id")
				.build())
		);

		givenRequestListServiceInstances(Flux.empty());

		givenRequestListDroplets("test-application-id", Mono.just(ListApplicationDropletsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(1)
				.build())
			.resource(DropletResource.builder()
				.result(StagedResult.builder()
					.processType("web", "test-command")
					.build())
				.id("test-droplet-id")
				.build())
			.build()));

		givenRequestCreateTask("test-application-id", "test-command", "test-droplet-id", this.deploymentProperties.getMemory(), "test-application", Mono.just(CreateTaskResponse.builder()
			.id("test-task-id")
			.build()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, this.resource, Collections.emptyMap());

		String taskId = this.launcher.launch(request);

		assertThat(taskId, equalTo("test-task-id"));
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationAndApplicationCreationFails() {
		givenRequestListApplications("test-application", Mono.just(ListApplicationsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(0)
				.build())
			.resource()
			.build()));

		givenRequestSpace("test-space", Mono.just(SpaceDetail.builder()
			.id("test-space-id")
			.name("test-name")
			.organization("test-organization")
			.build()));

		givenRequestCreateApplication(this.deploymentProperties.getBuildpack(), Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"), "test-application", "test-space-id",
			Mono.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, this.resource, Collections.emptyMap());

		this.launcher.launch(request);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationAndApplicationListingFails() {
		givenRequestListApplications("test-application", Mono.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, this.resource, Collections.emptyMap());

		this.launcher.launch(request);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationAndBindingFails() {

		givenRequestListApplications("test-application", Mono.just(ListApplicationsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(0)
				.build())
			.resource()
			.build()));

		givenRequestSpace("test-space", Mono.just(SpaceDetail.builder()
			.id("test-space-id")
			.name("test-name")
			.organization("test-organization")
			.build()));

		givenRequestCreateApplication(this.deploymentProperties.getBuildpack(), Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"), "test-application", "test-space-id",
			Mono.just(CreateApplicationResponse.builder()
				.id("test-application-id")
				.build()));

		givenRequestCreatePackage("test-application-id", Mono.just(CreatePackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestUploadPackage(this.path, "test-package-id", Mono.just(UploadPackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestGetPackage("test-package-id", Mono.just(GetPackageResponse.builder()
			.id("test-package-id")
			.state(State.READY)
			.build()));

		givenRequestStagePackage(this.deploymentProperties.getDisk(), this.deploymentProperties.getMemory(), "test-package-id", Mono.just(StagePackageResponse.builder()
			.id("test-droplet-id")
			.build()));

		givenRequestGetDroplet("test-droplet-id", Mono.just(GetDropletResponse.builder()
			.id("test-droplet-id")
			.state(org.cloudfoundry.client.v3.droplets.State.STAGED)
			.build()));

		givenRequestListServiceInstances(Flux.just(ServiceInstanceSummary.builder()
				.id("test-service-instance-id-1")
				.name("test-service-instance-1")
				.type(ServiceInstanceType.MANAGED)
				.build(),
			ServiceInstanceSummary.builder()
				.id("test-service-instance-id-2")
				.name("test-service-instance-2")
				.type(ServiceInstanceType.MANAGED)
				.build()));

		givenRequestCreateServiceBinding("test-application-id", "test-service-instance-id-2", Mono.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, this.resource,
			Collections.singletonMap(CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY, "test-service-instance-2"));

		this.launcher.launch(request);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationAndDropletCreationFails() {
		givenRequestListApplications("test-application", Mono.just(ListApplicationsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(0)
				.build())
			.resource()
			.build()));

		givenRequestSpace("test-space", Mono.just(SpaceDetail.builder()
			.id("test-space-id")
			.name("test-name")
			.organization("test-organization")
			.build()));

		givenRequestCreateApplication(this.deploymentProperties.getBuildpack(), Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"), "test-application", "test-space-id",
			Mono.just(CreateApplicationResponse.builder()
				.id("test-application-id")
				.build()));

		givenRequestCreatePackage("test-application-id", Mono.just(CreatePackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestUploadPackage(this.path, "test-package-id", Mono.just(UploadPackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestGetPackage("test-package-id", Mono.just(GetPackageResponse.builder()
			.id("test-package-id")
			.state(State.READY)
			.build()));

		givenRequestStagePackage(this.deploymentProperties.getDisk(), this.deploymentProperties.getMemory(), "test-package-id", Mono.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, this.resource, Collections.emptyMap());

		this.launcher.launch(request);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationAndPackageCreationFails() {
		givenRequestListApplications("test-application", Mono.just(ListApplicationsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(0)
				.build())
			.resource()
			.build()));

		givenRequestSpace("test-space", Mono.just(SpaceDetail.builder()
			.id("test-space-id")
			.name("test-name")
			.organization("test-organization")
			.build()));

		givenRequestCreateApplication(this.deploymentProperties.getBuildpack(), Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"), "test-application", "test-space-id",
			Mono.just(CreateApplicationResponse.builder()
				.id("test-application-id")
				.build()));

		givenRequestCreatePackage("test-application-id", Mono.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, this.resource, Collections.emptyMap());

		this.launcher.launch(request);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationAndPackageStatusFails() {
		givenRequestListApplications("test-application", Mono.just(ListApplicationsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(0)
				.build())
			.resource()
			.build()));

		givenRequestSpace("test-space", Mono.just(SpaceDetail.builder()
			.id("test-space-id")
			.name("test-name")
			.organization("test-organization")
			.build()));

		givenRequestCreateApplication(this.deploymentProperties.getBuildpack(), Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"), "test-application", "test-space-id",
			Mono.just(CreateApplicationResponse.builder()
				.id("test-application-id")
				.build()));

		givenRequestCreatePackage("test-application-id", Mono.just(CreatePackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestUploadPackage(this.path, "test-package-id", Mono.just(UploadPackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestGetPackage("test-package-id", Mono.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, this.resource, Collections.emptyMap());

		this.launcher.launch(request);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationAndPackageUploadFails() {
		givenRequestListApplications("test-application", Mono.just(ListApplicationsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(0)
				.build())
			.resource()
			.build()));

		givenRequestSpace("test-space", Mono.just(SpaceDetail.builder()
			.id("test-space-id")
			.name("test-name")
			.organization("test-organization")
			.build()));

		givenRequestCreateApplication(this.deploymentProperties.getBuildpack(), Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"), "test-application", "test-space-id",
			Mono.just(CreateApplicationResponse.builder()
				.id("test-application-id")
				.build()));

		givenRequestCreatePackage("test-application-id", Mono.just(CreatePackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestUploadPackage(this.path, "test-package-id", Mono.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, this.resource, Collections.emptyMap());

		this.launcher.launch(request);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationAndTaskCreationFails() {
		givenRequestListApplications("test-application", Mono.just(ListApplicationsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(0)
				.build())
			.resource()
			.build()));

		givenRequestSpace("test-space", Mono.just(SpaceDetail.builder()
			.id("test-space-id")
			.name("test-name")
			.organization("test-organization")
			.build()));

		givenRequestCreateApplication(this.deploymentProperties.getBuildpack(), Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"), "test-application", "test-space-id",
			Mono.just(CreateApplicationResponse.builder()
				.id("test-application-id")
				.build()));

		givenRequestCreatePackage("test-application-id", Mono.just(CreatePackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestUploadPackage(this.path, "test-package-id", Mono.just(UploadPackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestGetPackage("test-package-id", Mono.just(GetPackageResponse.builder()
			.id("test-package-id")
			.state(State.READY)
			.build()));

		givenRequestStagePackage(this.deploymentProperties.getDisk(), this.deploymentProperties.getMemory(), "test-package-id", Mono.just(StagePackageResponse.builder()
			.id("test-droplet-id")
			.build()));

		givenRequestGetDroplet("test-droplet-id",
			Mono.just(GetDropletResponse.builder()
				.id("test-droplet-id")
				.state(org.cloudfoundry.client.v3.droplets.State.STAGED)
				.build()),
			Mono.just(GetDropletResponse.builder()
				.result(StagedResult.builder()
					.processType("web", "test-command")
					.build())
				.id("test-droplet-id")
				.build()));

		givenRequestListServiceInstances(Flux.empty());

		givenRequestListDroplets("test-application-id", Mono.just(ListApplicationDropletsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(1)
				.build())
			.resource(DropletResource.builder()
				.result(StagedResult.builder()
					.processType("web", "test-command")
					.build())
				.id("test-droplet-id")
				.build())
			.build()));

		givenRequestCreateTask("test-application-id", "test-command", "test-droplet-id", this.deploymentProperties.getMemory(), "test-application",
			Mono.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, this.resource, Collections.emptyMap());

		this.launcher.launch(request);
	}

	@Test
	public void launchTaskWithNonExistentApplicationBindingOneService() {
		givenRequestListApplications("test-application", Mono.just(ListApplicationsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(0)
				.build())
			.resource()
			.build()));

		givenRequestSpace("test-space", Mono.just(SpaceDetail.builder()
			.id("test-space-id")
			.name("test-name")
			.organization("test-organization")
			.build()));

		givenRequestCreateApplication(this.deploymentProperties.getBuildpack(), Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"), "test-application", "test-space-id",
			Mono.just(CreateApplicationResponse.builder()
				.id("test-application-id")
				.build()));

		givenRequestCreatePackage("test-application-id", Mono.just(CreatePackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestUploadPackage(this.path, "test-package-id", Mono.just(UploadPackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestGetPackage("test-package-id", Mono.just(GetPackageResponse.builder()
			.id("test-package-id")
			.state(State.READY)
			.build()));

		givenRequestStagePackage(this.deploymentProperties.getDisk(), this.deploymentProperties.getMemory(), "test-package-id", Mono.just(StagePackageResponse.builder()
			.id("test-droplet-id")
			.build()));

		givenRequestGetDroplet("test-droplet-id",
			Mono.just(GetDropletResponse.builder()
				.id("test-droplet-id")
				.state(org.cloudfoundry.client.v3.droplets.State.STAGED)
				.build()),
			Mono.just(GetDropletResponse.builder()
				.result(StagedResult.builder()
					.processType("web", "test-command")
					.build())
				.id("test-droplet-id")
				.build()));

		givenRequestListServiceInstances(Flux.just(ServiceInstanceSummary.builder()
				.id("test-service-instance-id-1")
				.name("test-service-instance-1")
				.type(ServiceInstanceType.MANAGED)
				.build(),
			ServiceInstanceSummary.builder()
				.id("test-service-instance-id-2")
				.name("test-service-instance-2")
				.type(ServiceInstanceType.MANAGED)
				.build()));

		givenRequestCreateServiceBinding("test-application-id", "test-service-instance-id-2", Mono.just(CreateServiceBindingResponse.builder()
			.id("test-service-binding-id-2")
			.build()));

		givenRequestListDroplets("test-application-id", Mono.just(ListApplicationDropletsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(1)
				.build())
			.resource(DropletResource.builder()
				.result(StagedResult.builder()
					.processType("web", "test-command")
					.build())
				.id("test-droplet-id")
				.build())
			.build()));

		givenRequestCreateTask("test-application-id", "test-command", "test-droplet-id", this.deploymentProperties.getMemory(), "test-application", Mono.just(CreateTaskResponse.builder()
			.id("test-task-id")
			.build()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, this.resource,
			Collections.singletonMap(CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY, "test-service-instance-2"));

		String taskId = this.launcher.launch(request);

		assertThat(taskId, equalTo("test-task-id"));
		verifyRequestCreateServiceBinding("test-application-id", "test-service-instance-id-2");
	}

	@Test
	public void launchTaskWithNonExistentApplicationBindingThreeServices() {
		givenRequestListApplications("test-application", Mono.just(ListApplicationsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(0)
				.build())
			.resource()
			.build()));

		givenRequestSpace("test-space", Mono.just(SpaceDetail.builder()
			.id("test-space-id")
			.name("test-name")
			.organization("test-organization")
			.build()));

		givenRequestCreateApplication(this.deploymentProperties.getBuildpack(), Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"), "test-application", "test-space-id",
			Mono.just(CreateApplicationResponse.builder()
				.id("test-application-id")
				.build()));

		givenRequestCreatePackage("test-application-id", Mono.just(CreatePackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestUploadPackage(this.path, "test-package-id", Mono.just(UploadPackageResponse.builder()
			.id("test-package-id")
			.build()));

		givenRequestGetPackage("test-package-id", Mono.just(GetPackageResponse.builder()
			.id("test-package-id")
			.state(State.READY)
			.build()));

		givenRequestStagePackage(this.deploymentProperties.getDisk(), this.deploymentProperties.getMemory(), "test-package-id", Mono.just(StagePackageResponse.builder()
			.id("test-droplet-id")
			.build()));

		givenRequestGetDroplet("test-droplet-id",
			Mono.just(GetDropletResponse.builder()
				.id("test-droplet-id")
				.state(org.cloudfoundry.client.v3.droplets.State.STAGED)
				.build()),
			Mono.just(GetDropletResponse.builder()
				.result(StagedResult.builder()
					.processType("web", "test-command")
					.build())
				.id("test-droplet-id")
				.build()));

		givenRequestListServiceInstances(Flux.just(ServiceInstanceSummary.builder()
				.id("test-service-instance-id-1")
				.name("test-service-instance-1")
				.type(ServiceInstanceType.MANAGED)
				.build(),
			ServiceInstanceSummary.builder()
				.id("test-service-instance-id-2")
				.name("test-service-instance-2")
				.type(ServiceInstanceType.MANAGED)
				.build(),
			ServiceInstanceSummary.builder()
				.id("test-service-instance-id-3")
				.name("test-service-instance-3")
				.type(ServiceInstanceType.MANAGED)
				.build()));

		givenRequestCreateServiceBinding("test-application-id", "test-service-instance-id-1", Mono.just(CreateServiceBindingResponse.builder()
			.id("test-service-binding-id-1")
			.build()));

		givenRequestCreateServiceBinding("test-application-id", "test-service-instance-id-2", Mono.just(CreateServiceBindingResponse.builder()
			.id("test-service-binding-id-2")
			.build()));

		givenRequestCreateServiceBinding("test-application-id", "test-service-instance-id-3", Mono.just(CreateServiceBindingResponse.builder()
			.id("test-service-binding-id-3")
			.build()));

		givenRequestListDroplets("test-application-id", Mono.just(ListApplicationDropletsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(1)
				.build())
			.resource(DropletResource.builder()
				.result(StagedResult.builder()
					.processType("web", "test-command")
					.build())
				.id("test-droplet-id")
				.build())
			.build()));

		givenRequestCreateTask("test-application-id", "test-command", "test-droplet-id", this.deploymentProperties.getMemory(), "test-application", Mono.just(CreateTaskResponse.builder()
			.id("test-task-id")
			.build()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, this.resource,
			Collections.singletonMap(CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY, "test-service-instance-1,test-service-instance-2,test-service-instance-3"));

		String taskId = this.launcher.launch(request);

		assertThat(taskId, equalTo("test-task-id"));
		verifyRequestCreateServiceBinding("test-application-id", "test-service-instance-id-1");
		verifyRequestCreateServiceBinding("test-application-id", "test-service-instance-id-2");
		verifyRequestCreateServiceBinding("test-application-id", "test-service-instance-id-3");
	}

	@Before
	public void setUp() throws IOException {
		MockitoAnnotations.initMocks(this);
		given(this.client.applicationsV3()).willReturn(this.applicationsV3);
		given(this.client.droplets()).willReturn(this.droplets);
		given(this.client.packages()).willReturn(this.packages);
		given(this.client.serviceBindingsV3()).willReturn(this.serviceBindingsV3);
		given(this.client.tasks()).willReturn(this.tasks);

		given(this.operations.services()).willReturn(this.services);
		given(this.operations.spaces()).willReturn(this.spaces);

		given(this.resource.getFile()).willReturn(this.file);
		given(this.file.toPath()).willReturn(this.path);

		this.deploymentProperties.setApiTimeout(1);
		this.launcher = new CloudFoundry2620AndEarlierTaskLauncher(this.client, this.deploymentProperties, this.operations, "test-space");
	}

	@Test
	public void status() {
		givenRequestGetTask("test-task-id", Mono.just(GetTaskResponse.builder()
			.id("test-task-id")
			.state(org.cloudfoundry.client.v3.tasks.State.SUCCEEDED_STATE)
			.build()));

		TaskStatus status = this.launcher.status("test-task-id");

		assertThat(status.getState(), equalTo(LaunchState.complete));
	}

	@Test
	public void testStatusTimeout() {
		givenRequestGetTask("test-task-id", Mono
			.delay(Duration.ofSeconds(2))
			.then(Mono.just(GetTaskResponse.builder()
				.id("test-task-id")
				.state(org.cloudfoundry.client.v3.tasks.State.SUCCEEDED_STATE)
				.build())));

		assertThat(this.launcher.status("test-task-id").getState(), equalTo(LaunchState.error));
	}

	@Test
	public void testDestroyEvenWithBoundServices() {
		givenRequestListApplications("test-application", Mono.just(ListApplicationsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(1)
				.build())
			.resource(ApplicationResource.builder()
				.id("test-application-id")
				.build())
			.build()));

		givenRequestListServiceBindings("test-application-id", Mono.just(ListServiceBindingsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(1)
				.build())
			.resource(ServiceBindingResource.builder()
				.id("test-service-binding-id")
				.build())
			.build()
		));

		givenRequestDeleteServiceBinding("test-service-binding-id");
		givenRequestDeleteApplication("test-application-id");

		this.launcher.destroy("test-application");
		verifyRequestDeleteServiceBinding("test-service-binding-id");
	}

	@Test
	public void testDestroyInexistentApp() {
		givenRequestListApplications("test-application", Mono.just(ListApplicationsResponse.builder()
			.pagination(Pagination.builder()
				.totalResults(0)
				.build())
			.resource()
			.build()));

		this.launcher.destroy("test-application");
	}

	private void givenRequestCancelTask(String taskId, Mono<CancelTaskResponse> response) {
		given(this.client.tasks()
			.cancel(CancelTaskRequest.builder()
				.taskId(taskId)
				.build()))
			.willReturn(response);
	}

	private void givenRequestCreateApplication(String buildpack, Map<String, String> environmentVariables, String name, String spaceId, Mono<CreateApplicationResponse> response) {
		given(this.client.applicationsV3()
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
				.build()))
			.willReturn(response);
	}

	private void givenRequestCreatePackage(String applicationId, Mono<CreatePackageResponse> response) {
		given(this.client.packages()
			.create(CreatePackageRequest.builder()
				.applicationId(applicationId)
				.type(PackageType.BITS)
				.build()))
			.willReturn(response);
	}

	private void givenRequestCreateServiceBinding(String applicationId, String serviceInstanceId, Mono<CreateServiceBindingResponse> response) {
		given(this.client.serviceBindingsV3()
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
				.build()))
			.willReturn(response);
	}

	private void givenRequestCreateTask(String applicationId, String command, String dropletId, String memory, String name, Mono<CreateTaskResponse> response) {
		given(this.client.tasks()
			.create(CreateTaskRequest.builder()
				.applicationId(applicationId)
				.command(command)
				.dropletId(dropletId)
				.memoryInMb((int) ByteSizeUtils.parseToMebibytes(memory))
				.name(name)
				.build()))
			.willReturn(response);
	}

	private void givenRequestGetDroplet(String dropletId, Mono<GetDropletResponse> response, Mono<GetDropletResponse>... responses) {
		given(this.client.droplets()
			.get(GetDropletRequest.builder()
				.dropletId(dropletId)
				.build()))
			.willReturn(response, responses);
	}

	private void givenRequestGetPackage(String packageId, Mono<GetPackageResponse> response) {
		given(this.client.packages()
			.get(GetPackageRequest.builder()
				.packageId(packageId)
				.build()))
			.willReturn(response);
	}

	private void givenRequestGetTask(String taskId, Mono<GetTaskResponse> response) {
		given(this.client.tasks()
			.get(GetTaskRequest.builder()
				.taskId(taskId)
				.build()))
			.willReturn(response);
	}

	private void givenRequestListApplications(String name, Mono<ListApplicationsResponse> response) {
		given(this.client.applicationsV3()
			.list(ListApplicationsRequest.builder()
				.name(name)
				.page(1)
				.build()))
			.willReturn(response);
	}

	private void givenRequestListDroplets(String applicationId, Mono<ListApplicationDropletsResponse> response) {
		given(this.client.applicationsV3()
			.listDroplets(ListApplicationDropletsRequest.builder()
				.applicationId(applicationId)
				.page(1)
				.build()))
			.willReturn(response);
	}

	private void givenRequestListServiceInstances(Flux<ServiceInstanceSummary> response) {
		given(this.operations.services()
			.listInstances())
			.willReturn(response);
	}

	private void givenRequestSpace(String space, Mono<SpaceDetail> response) {
		given(this.operations.spaces()
			.get(GetSpaceRequest.builder()
				.name(space)
				.build()))
			.willReturn(response);
	}

	private void givenRequestListServiceBindings(String appId, Mono<ListServiceBindingsResponse> response) {
		given(this.client.serviceBindingsV3()
			.list(ListServiceBindingsRequest.builder()
				.applicationId(appId)
				.page(1)
				.build()))
			.willReturn(response);
	}

	private void givenRequestDeleteServiceBinding(String sbId) {
		given(this.client.serviceBindingsV3()
			.delete(DeleteServiceBindingRequest.builder()
				.serviceBindingId(sbId)
				.build()))
			.willReturn(Mono.empty());
	}

	private void givenRequestDeleteApplication(String appId) {
		given(this.client.applicationsV3()
			.delete(DeleteApplicationRequest.builder()
				.applicationId(appId)
				.build()))
			.willReturn(Mono.empty());
	}

	private void givenRequestStagePackage(String disk, String memory, String packageId, Mono<StagePackageResponse> response) {
		given(this.client.packages()
			.stage(StagePackageRequest.builder()
				.packageId(packageId)
				.stagingDiskInMb((int) ByteSizeUtils.parseToMebibytes(disk))
				.stagingMemoryInMb((int) ByteSizeUtils.parseToMebibytes(memory))
				.build()))
			.willReturn(response);
	}

	private void givenRequestUploadPackage(Path bits, String packageId, Mono<UploadPackageResponse> response) {
		given(this.client.packages()
			.upload(UploadPackageRequest.builder()
				.bits(bits)
				.packageId(packageId)
				.build()))
			.willReturn(response);
	}

	private void verifyRequestCreateServiceBinding(String applicationId, String serviceInstanceId) {
		verify(this.client.serviceBindingsV3())
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

	private void verifyRequestDeleteServiceBinding(String serviceBindingId) {
		verify(this.client.serviceBindingsV3())
			.delete(DeleteServiceBindingRequest.builder()
				.serviceBindingId(serviceBindingId)
			.build());
	}

}
