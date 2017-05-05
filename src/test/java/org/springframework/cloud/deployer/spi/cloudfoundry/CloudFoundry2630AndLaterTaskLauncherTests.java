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

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.ApplicationsV2;
import org.cloudfoundry.client.v2.applications.SummaryApplicationResponse;
import org.cloudfoundry.client.v3.tasks.CancelTaskRequest;
import org.cloudfoundry.client.v3.tasks.CancelTaskResponse;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskResponse;
import org.cloudfoundry.client.v3.tasks.GetTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.cloudfoundry.client.v3.tasks.Tasks;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.ApplicationHealthCheck;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import org.cloudfoundry.operations.applications.Applications;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.PushApplicationManifestRequest;
import org.cloudfoundry.operations.applications.StopApplicationRequest;
import org.cloudfoundry.operations.services.Services;
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
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import org.springframework.cloud.deployer.spi.util.ByteSizeUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * @author Michael Minella
 * @author Ben Hale
 */
public class CloudFoundry2630AndLaterTaskLauncherTests {

	private final CloudFoundryDeploymentProperties deploymentProperties = new CloudFoundryDeploymentProperties();

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Applications applications;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private ApplicationsV2 applicationsV2;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private CloudFoundryClient client;

	private CloudFoundry2630AndLaterTaskLauncher launcher;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private CloudFoundryOperations operations;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Services services;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Spaces spaces;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Tasks tasks;

	@Before
	public void setUp() throws IOException {
		MockitoAnnotations.initMocks(this);
		given(this.client.applicationsV2()).willReturn(this.applicationsV2);
		given(this.client.tasks()).willReturn(this.tasks);

		given(this.operations.applications()).willReturn(this.applications);
		given(this.operations.services()).willReturn(this.services);
		given(this.operations.spaces()).willReturn(this.spaces);

		this.deploymentProperties.setApiTimeout(1);
		this.deploymentProperties.setStatusTimeout(1_250L);
		this.launcher = new CloudFoundry2630AndLaterTaskLauncher(this.client, this.deploymentProperties, this.operations, mock(RuntimeEnvironmentInfo.class));
	}

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
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		givenRequestListApplications(Flux.just(ApplicationSummary.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.build()));

		givenRequestGetApplicationSummary("test-application-id", Mono.just(SummaryApplicationResponse.builder()
			.id("test-application-id")
			.detectedStartCommand("test-command")
			.build()));

		givenRequestCreateTask("test-application-id", "test-command", this.deploymentProperties.getMemory(), "test-application", Mono.just(CreateTaskResponse.builder()
			.id("test-task-id")
			.build()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, Collections.emptyMap());

		String taskId = this.launcher.launch(request);

		assertThat(taskId, equalTo("test-task-id"));
	}

	@Test
	public void launchTaskWithNonExistentApplication() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		givenRequestListApplications(Flux.empty());

		givenRequestPushApplication(
			PushApplicationManifestRequest.builder()
				.manifest(ApplicationManifest.builder()
					.path(resource.getFile().toPath())
					.buildpack(deploymentProperties.getBuildpack())
					.command("echo '*** First run of container to allow droplet creation.***' && sleep 300")
					.disk((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getDisk()))
					.environmentVariable("SPRING_APPLICATION_JSON", "{}")
					.healthCheckType(ApplicationHealthCheck.NONE)
					.memory((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getMemory()))
					.name("test-application")
					.noRoute(true)
					.services(Collections.emptySet())
					.build())
				.stagingTimeout(this.deploymentProperties.getStagingTimeout())
				.startupTimeout(this.deploymentProperties.getStartupTimeout())
				.build(), Mono.empty());

		givenRequestGetApplication("test-application", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.build()));

		givenRequestStopApplication("test-application", Mono.empty());

		givenRequestGetApplicationSummary("test-application-id", Mono.just(SummaryApplicationResponse.builder()
			.id("test-application-id")
			.detectedStartCommand("test-command")
			.build()));

		givenRequestCreateTask("test-application-id", "test-command", this.deploymentProperties.getMemory(), "test-application", Mono.just(CreateTaskResponse.builder()
			.id("test-task-id")
			.build()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, Collections.emptyMap());

		String taskId = this.launcher.launch(request);

		assertThat(taskId, equalTo("test-task-id"));
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationAndApplicationListingFails() {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		givenRequestListApplications(Flux.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, Collections.emptyMap());

		this.launcher.launch(request);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationAndPushFails() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		givenRequestListApplications(Flux.empty());

		givenRequestPushApplication(
			PushApplicationManifestRequest.builder()
				.manifest(ApplicationManifest.builder()
					.path(resource.getFile().toPath())
					.buildpack(deploymentProperties.getBuildpack())
					.command("echo '*** First run of container to allow droplet creation.***' && sleep 300")
					.disk((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getDisk()))
					.environmentVariable("SPRING_APPLICATION_JSON", "{}")
					.healthCheckType(ApplicationHealthCheck.NONE)
					.memory((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getMemory()))
					.name("test-application")
					.noRoute(true)
					.services(Collections.emptySet())
					.build())
				.stagingTimeout(this.deploymentProperties.getStagingTimeout())
				.startupTimeout(this.deploymentProperties.getStartupTimeout())
				.build(), Mono.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, Collections.emptyMap());

		this.launcher.launch(request);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationAndRetrievingApplicationSummaryFails() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		givenRequestListApplications(Flux.empty());

		givenRequestPushApplication(
			PushApplicationManifestRequest.builder()
				.manifest(ApplicationManifest.builder()
					.path(resource.getFile().toPath())
					.buildpack(deploymentProperties.getBuildpack())
					.command("echo '*** First run of container to allow droplet creation.***' && sleep 300")
					.disk((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getDisk()))
					.environmentVariable("SPRING_APPLICATION_JSON", "{}")
					.healthCheckType(ApplicationHealthCheck.NONE)
					.memory((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getMemory()))
					.name("test-application")
					.noRoute(true)
					.services(Collections.emptySet())
					.build())
				.stagingTimeout(this.deploymentProperties.getStagingTimeout())
				.startupTimeout(this.deploymentProperties.getStartupTimeout())
				.build(), Mono.empty());

		givenRequestGetApplication("test-application", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.build()));

		givenRequestStopApplication("test-application", Mono.empty());

		givenRequestGetApplicationSummary("test-application-id", Mono.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, Collections.emptyMap());

		this.launcher.launch(request);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationAndStoppingApplicationFails() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		givenRequestListApplications(Flux.empty());

		givenRequestPushApplication(
			PushApplicationManifestRequest.builder()
				.manifest(ApplicationManifest.builder()
					.path(resource.getFile().toPath())
					.buildpack(deploymentProperties.getBuildpack())
					.command("echo '*** First run of container to allow droplet creation.***' && sleep 300")
					.disk((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getDisk()))
					.environmentVariable("SPRING_APPLICATION_JSON", "{}")
					.healthCheckType(ApplicationHealthCheck.NONE)
					.memory((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getMemory()))
					.name("test-application")
					.noRoute(true)
					.services(Collections.emptySet())
					.build())
				.stagingTimeout(this.deploymentProperties.getStagingTimeout())
				.startupTimeout(this.deploymentProperties.getStartupTimeout())
				.build(), Mono.empty());

		givenRequestGetApplication("test-application", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.build()));

		givenRequestStopApplication("test-application", Mono.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, Collections.emptyMap());

		this.launcher.launch(request);
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationAndTaskCreationFails() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		givenRequestListApplications(Flux.empty());

		givenRequestPushApplication(PushApplicationManifestRequest.builder()
			.manifest(ApplicationManifest.builder()
				.path(resource.getFile().toPath())
				.buildpack(deploymentProperties.getBuildpack())
				.command("echo '*** First run of container to allow droplet creation.***' && sleep 300")
				.disk((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getDisk()))
				.environmentVariable("SPRING_APPLICATION_JSON", "{}")
				.healthCheckType(ApplicationHealthCheck.NONE)
				.memory((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getMemory()))
				.name("test-application")
				.noRoute(true)
				.services(Collections.emptySet())
				.build())
			.stagingTimeout(this.deploymentProperties.getStagingTimeout())
			.startupTimeout(this.deploymentProperties.getStartupTimeout())
			.build(), Mono.empty());

		givenRequestGetApplication("test-application", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.build()));

		givenRequestStopApplication("test-application", Mono.empty());

		givenRequestGetApplicationSummary("test-application-id", Mono.just(SummaryApplicationResponse.builder()
			.id("test-application-id")
			.detectedStartCommand("test-command")
			.build()));

		givenRequestCreateTask("test-application-id", "test-command", this.deploymentProperties.getMemory(), "test-application", Mono.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, Collections.emptyMap());

		this.launcher.launch(request);
	}

	@Test
	public void launchTaskWithNonExistentApplicationBindingOneService() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		givenRequestListApplications(Flux.empty());

		givenRequestPushApplication(PushApplicationManifestRequest.builder()
			.manifest(ApplicationManifest.builder()
				.path(resource.getFile().toPath())
				.buildpack(deploymentProperties.getBuildpack())
				.command("echo '*** First run of container to allow droplet creation.***' && sleep 300")
				.disk((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getDisk()))
				.environmentVariable("SPRING_APPLICATION_JSON", "{}")
				.healthCheckType(ApplicationHealthCheck.NONE)
				.memory((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getMemory()))
				.name("test-application")
				.noRoute(true)
				.service("test-service-instance-2")
				.build())
			.stagingTimeout(this.deploymentProperties.getStagingTimeout())
			.startupTimeout(this.deploymentProperties.getStartupTimeout())
			.build(), Mono.empty());

		givenRequestGetApplication("test-application", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.build()));

		givenRequestStopApplication("test-application", Mono.empty());

		givenRequestGetApplicationSummary("test-application-id", Mono.just(SummaryApplicationResponse.builder()
			.id("test-application-id")
			.detectedStartCommand("test-command")
			.build()));

		givenRequestCreateTask("test-application-id", "test-command", this.deploymentProperties.getMemory(), "test-application", Mono.just(CreateTaskResponse.builder()
			.id("test-task-id")
			.build()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource,
			Collections.singletonMap(CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY, "test-service-instance-2"));

		String taskId = this.launcher.launch(request);

		assertThat(taskId, equalTo("test-task-id"));
	}

	@Test
	public void launchTaskWithNonExistentApplicationBindingThreeServices() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		givenRequestListApplications(Flux.empty());

		givenRequestPushApplication(PushApplicationManifestRequest.builder()
			.manifest(ApplicationManifest.builder()
				.path(resource.getFile().toPath())
				.buildpack(deploymentProperties.getBuildpack())
				.command("echo '*** First run of container to allow droplet creation.***' && sleep 300")
				.disk((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getDisk()))
				.environmentVariable("SPRING_APPLICATION_JSON", "{}")
				.healthCheckType(ApplicationHealthCheck.NONE)
				.memory((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getMemory()))
				.name("test-application")
				.noRoute(true)
				.service("test-service-instance-1")
				.service("test-service-instance-2")
				.service("test-service-instance-3")
				.build())
			.stagingTimeout(this.deploymentProperties.getStagingTimeout())
			.startupTimeout(this.deploymentProperties.getStartupTimeout())
			.build(), Mono.empty());

		givenRequestGetApplication("test-application", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.build()));

		givenRequestStopApplication("test-application", Mono.empty());

		givenRequestGetApplicationSummary("test-application-id", Mono.just(SummaryApplicationResponse.builder()
			.id("test-application-id")
			.detectedStartCommand("test-command")
			.build()));

		givenRequestCreateTask("test-application-id", "test-command", this.deploymentProperties.getMemory(), "test-application", Mono.just(CreateTaskResponse.builder()
			.id("test-task-id")
			.build()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource,
			Collections.singletonMap(CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY, "test-service-instance-1,test-service-instance-2,test-service-instance-3"));

		String taskId = this.launcher.launch(request);

		assertThat(taskId, equalTo("test-task-id"));
	}

	@Test(expected = UnsupportedOperationException.class)
	public void launchTaskWithNonExistentApplicationRetrievalFails() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		givenRequestListApplications(Flux.empty());

		givenRequestPushApplication(PushApplicationManifestRequest.builder()
			.manifest(ApplicationManifest.builder()
				.path(resource.getFile().toPath())
				.buildpack(deploymentProperties.getBuildpack())
				.command("echo '*** First run of container to allow droplet creation.***' && sleep 300")
				.disk((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getDisk()))
				.environmentVariable("SPRING_APPLICATION_JSON", "{}")
				.healthCheckType(ApplicationHealthCheck.NONE)
				.memory((int) ByteSizeUtils.parseToMebibytes(this.deploymentProperties.getMemory()))
				.name("test-application")
				.noRoute(true)
				.services(Collections.emptySet())
				.build())
			.stagingTimeout(this.deploymentProperties.getStagingTimeout())
			.startupTimeout(this.deploymentProperties.getStartupTimeout())
			.build(), Mono.empty());

		givenRequestStopApplication("test-application", Mono.empty());

		givenRequestGetApplication("test-application", Mono.error(new UnsupportedOperationException()));

		AppDefinition definition = new AppDefinition("test-application", null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, Collections.emptyMap());

		this.launcher.launch(request);
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
		// Delay twice as much as 40% of statusTimeout, which is what the deployer uses
		long delay = (long) (this.deploymentProperties.getStatusTimeout() * .4f * 2);

		givenRequestGetTask("test-task-id", Mono
			.delay(Duration.ofMillis(delay))
			.then(Mono.just(GetTaskResponse.builder()
				.id("test-task-id")
				.state(org.cloudfoundry.client.v3.tasks.State.SUCCEEDED_STATE)
				.build())));

		assertThat(this.launcher.status("test-task-id").getState(), equalTo(LaunchState.error));
	}

	@Test
	public void testDestroy() {
		givenRequestDeleteApplication("test-application");

		this.launcher.destroy("test-application");
	}

	private void givenRequestCancelTask(String taskId, Mono<CancelTaskResponse> response) {
		given(this.client.tasks()
			.cancel(CancelTaskRequest.builder()
				.taskId(taskId)
				.build()))
			.willReturn(response);
	}

	private void givenRequestCreateTask(String applicationId, String command, String memory, String name, Mono<CreateTaskResponse> response) {
		given(this.client.tasks()
			.create(CreateTaskRequest.builder()
				.applicationId(applicationId)
				.command(command)
				.memoryInMb((int) ByteSizeUtils.parseToMebibytes(memory))
				.name(name)
				.build()))
			.willReturn(response);
	}

	private void givenRequestDeleteApplication(String appName) {
		given(this.operations.applications()
			.delete(DeleteApplicationRequest.builder()
				.name(appName)
				.deleteRoutes(true)
				.build()))
			.willReturn(Mono.empty());
	}

	private void givenRequestGetApplication(String name, Mono<ApplicationDetail> response) {
		given(this.operations.applications()
			.get(GetApplicationRequest.builder()
				.name(name)
				.build()))
			.willReturn(response);
	}

	private void givenRequestGetApplicationSummary(String applicationId, Mono<SummaryApplicationResponse> response) {
		given(this.client.applicationsV2()
			.summary(org.cloudfoundry.client.v2.applications.SummaryApplicationRequest.builder()
				.applicationId(applicationId)
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

	private void givenRequestListApplications(Flux<ApplicationSummary> response) {
		given(this.operations.applications()
			.list())
			.willReturn(response);
	}

	private void givenRequestPushApplication(PushApplicationManifestRequest request, Mono<Void> response) {
		given(this.operations.applications()
			.pushManifest(request))
			.willReturn(response);
	}

	private void givenRequestStopApplication(String name, Mono<Void> response) {
		given(this.operations.applications()
			.stop(StopApplicationRequest.builder()
				.name(name)
				.build()))
			.willReturn(response);
	}

}
