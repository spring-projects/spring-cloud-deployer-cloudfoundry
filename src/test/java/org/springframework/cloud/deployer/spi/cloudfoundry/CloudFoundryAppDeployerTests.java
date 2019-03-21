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

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.cloud.deployer.spi.app.AppDeployer.COUNT_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.app.AppDeployer.GROUP_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.BUILDPACK_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.DOMAIN_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.HEALTHCHECK_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.HOST_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.NO_ROUTE_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.ROUTE_PATH_PROPERTY;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.ApplicationsV2;
import org.cloudfoundry.client.v2.applications.UpdateApplicationRequest;
import org.cloudfoundry.client.v2.applications.UpdateApplicationResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.ApplicationHealthCheck;
import org.cloudfoundry.operations.applications.Applications;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.InstanceDetail;
import org.cloudfoundry.operations.applications.PushApplicationRequest;
import org.cloudfoundry.operations.applications.StartApplicationRequest;
import org.cloudfoundry.operations.services.BindServiceInstanceRequest;
import org.cloudfoundry.operations.services.Services;
import org.cloudfoundry.util.FluentMap;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * Unit tests for the {@link CloudFoundryAppDeployer}.
 *
 * @author Greg Turnquist
 * @author Eric Bottard
 * @author Ilayaperumal Gopinathan
 * @author Ben Hale
 */
public class CloudFoundryAppDeployerTests {

	private final CloudFoundryDeploymentProperties deploymentProperties = new CloudFoundryDeploymentProperties();

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private AppNameGenerator applicationNameGenerator;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Applications applications;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private ApplicationsV2 applicationsV2;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private CloudFoundryClient client;

	private CloudFoundryAppDeployer deployer;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private CloudFoundryOperations operations;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Services services;

	@SuppressWarnings("unchecked")
	@Test
	public void deploy() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		given(this.applicationNameGenerator.generateAppName("test-application")).willReturn("test-application-id");

		givenRequestGetApplication("test-application-id",
			Mono.error(new IllegalArgumentException()),
			Mono.just(ApplicationDetail.builder()
				.diskQuota(0)
				.id("test-application-id")
				.instances(1)
				.memoryLimit(0)
				.name("test-application")
				.requestedState("RUNNING")
				.runningInstances(0)
				.stack("test-stack")
				.build()));

		givenRequestPushApplication(PushApplicationRequest.builder()
			.application(resource.getFile().toPath())
			.buildpack("https://github.com/cloudfoundry/java-buildpack.git")
			.diskQuota(1024)
			.instances(1)
			.memory(1024)
			.name("test-application-id")
			.noStart(true)
			.build(), Mono.empty());

		givenRequestUpdateApplication("test-application-id", Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"), Mono.empty());

		givenRequestBindService("test-application-id", "test-service-1", Mono.empty());
		givenRequestBindService("test-application-id", "test-service-2", Mono.empty());

		givenRequestStartApplication("test-application-id", null, null, Mono.empty());

		String deploymentId = this.deployer.deploy(new AppDeploymentRequest(
			new AppDefinition("test-application", Collections.emptyMap()),
			resource,
			Collections.emptyMap()));

		assertThat(deploymentId, equalTo("test-application-id"));

		verifyRequestUpdateApplication("test-application-id", Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"));

		verifyRequestBindService("test-application-id", "test-service-1");
		verifyRequestBindService("test-application-id", "test-service-2");

		verifyRequestStartApplication("test-application-id", null, null);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void deployWithAdditionalProperties() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		given(this.applicationNameGenerator.generateAppName("test-application")).willReturn("test-application-id");

		givenRequestGetApplication("test-application-id",
			Mono.error(new IllegalArgumentException()),
			Mono.just(ApplicationDetail.builder()
				.diskQuota(0)
				.id("test-application-id")
				.instances(1)
				.memoryLimit(0)
				.name("test-application")
				.requestedState("RUNNING")
				.runningInstances(0)
				.stack("test-stack")
				.build()));

		givenRequestPushApplication(PushApplicationRequest.builder()
			.application(resource.getFile().toPath())
			.buildpack("https://github.com/cloudfoundry/java-buildpack.git")
			.diskQuota(1024)
			.instances(1)
			.memory(1024)
			.name("test-application-id")
			.noStart(true)
			.build(), Mono.empty());

		givenRequestUpdateApplication("test-application-id", Collections.singletonMap("test-key-1", "test-value-1"), Mono.empty());

		givenRequestBindService("test-application-id", "test-service-1", Mono.empty());
		givenRequestBindService("test-application-id", "test-service-2", Mono.empty());

		givenRequestStartApplication("test-application-id", null, null, Mono.empty());

		String deploymentId = this.deployer.deploy(new AppDeploymentRequest(
			new AppDefinition("test-application", Collections.singletonMap("test-key-1", "test-value-1")),
			resource,
			FluentMap.<String, String>builder()
				.entry("test-key-2", "test-value-2")
				.entry(CloudFoundryDeploymentProperties.USE_SPRING_APPLICATION_JSON_KEY, String.valueOf(false))
				.build()));

		assertThat(deploymentId, equalTo("test-application-id"));

		verifyRequestUpdateApplication("test-application-id", Collections.singletonMap("test-key-1", "test-value-1"));

		verifyRequestBindService("test-application-id", "test-service-1");
		verifyRequestBindService("test-application-id", "test-service-2");

		verifyRequestStartApplication("test-application-id", null, null);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void deployWithAdditionalPropertiesInSpringApplicationJson() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		given(this.applicationNameGenerator.generateAppName("test-application")).willReturn("test-application-id");

		givenRequestGetApplication("test-application-id",
			Mono.error(new IllegalArgumentException()),
			Mono.just(ApplicationDetail.builder()
				.diskQuota(0)
				.id("test-application-id")
				.instances(1)
				.memoryLimit(0)
				.name("test-application")
				.requestedState("RUNNING")
				.runningInstances(0)
				.stack("test-stack")
				.build()));

		givenRequestPushApplication(PushApplicationRequest.builder()
			.application(resource.getFile().toPath())
			.buildpack("https://github.com/cloudfoundry/java-buildpack.git")
			.diskQuota(1024)
			.instances(1)
			.memory(1024)
			.name("test-application-id")
			.noStart(true)
			.build(), Mono.empty());

		givenRequestUpdateApplication("test-application-id", Collections.singletonMap("SPRING_APPLICATION_JSON", "{\"test-key-1\":\"test-value-1\"}"), Mono.empty());

		givenRequestBindService("test-application-id", "test-service-1", Mono.empty());
		givenRequestBindService("test-application-id", "test-service-2", Mono.empty());

		givenRequestStartApplication("test-application-id", null, null, Mono.empty());

		String deploymentId = this.deployer.deploy(new AppDeploymentRequest(
			new AppDefinition("test-application", Collections.singletonMap("test-key-1", "test-value-1")),
			resource,
			Collections.singletonMap("test-key-2", "test-value-2")));

		assertThat(deploymentId, equalTo("test-application-id"));

		verifyRequestUpdateApplication("test-application-id", Collections.singletonMap("SPRING_APPLICATION_JSON", "{\"test-key-1\":\"test-value-1\"}"));

		verifyRequestBindService("test-application-id", "test-service-1");
		verifyRequestBindService("test-application-id", "test-service-2");

		verifyRequestStartApplication("test-application-id", null, null);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void deployWithApplicationDeploymentProperties() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		given(this.applicationNameGenerator.generateAppName("test-application")).willReturn("test-application-id");

		givenRequestGetApplication("test-application-id",
			Mono.error(new IllegalArgumentException()),
			Mono.just(ApplicationDetail.builder()
				.diskQuota(0)
				.id("test-application-id")
				.instances(1)
				.memoryLimit(0)
				.name("test-application")
				.requestedState("RUNNING")
				.runningInstances(0)
				.stack("test-stack")
				.build()));

		givenRequestPushApplication(PushApplicationRequest.builder()
			.application(resource.getFile().toPath())
			.buildpack("test-buildpack")
			.diskQuota(0)
			.domain("test-domain")
			.healthCheckType(ApplicationHealthCheck.NONE)
			.host("test-host")
			.instances(0)
			.memory(0)
			.name("test-application-id")
			.noRoute(false)
			.noStart(true)
			.routePath("test-route-path")
			.build(), Mono.empty());

		givenRequestUpdateApplication("test-application-id", Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"), Mono.empty());

		givenRequestBindService("test-application-id", "test-service-1", Mono.empty());
		givenRequestBindService("test-application-id", "test-service-2", Mono.empty());

		givenRequestStartApplication("test-application-id", null, null, Mono.empty());

		String deploymentId = this.deployer.deploy(new AppDeploymentRequest(
			new AppDefinition("test-application", Collections.emptyMap()),
			resource,
			FluentMap.<String, String>builder()
				.entry(BUILDPACK_PROPERTY_KEY, "test-buildpack")
				.entry(AppDeployer.DISK_PROPERTY_KEY, "0")
				.entry(DOMAIN_PROPERTY, "test-domain")
				.entry(HEALTHCHECK_PROPERTY_KEY, "none")
				.entry(HOST_PROPERTY, "test-host")
				.entry(COUNT_PROPERTY_KEY, "0")
				.entry(AppDeployer.MEMORY_PROPERTY_KEY, "0")
				.entry(NO_ROUTE_PROPERTY, "false")
				.entry(ROUTE_PATH_PROPERTY, "test-route-path")
				.build()));

		assertThat(deploymentId, equalTo("test-application-id"));

		verifyRequestUpdateApplication("test-application-id", Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"));

		verifyRequestBindService("test-application-id", "test-service-1");
		verifyRequestBindService("test-application-id", "test-service-2");

		verifyRequestStartApplication("test-application-id", null, null);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void deployWithCustomDeploymentProperties() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		given(this.applicationNameGenerator.generateAppName("test-application")).willReturn("test-application-id");

		givenRequestGetApplication("test-application-id",
			Mono.error(new IllegalArgumentException()),
			Mono.just(ApplicationDetail.builder()
				.diskQuota(0)
				.id("test-application-id")
				.instances(1)
				.memoryLimit(0)
				.name("test-application")
				.requestedState("RUNNING")
				.runningInstances(0)
				.stack("test-stack")
				.build()));

		this.deploymentProperties.setBuildpack("test-buildpack");
		this.deploymentProperties.setDisk("0");
		this.deploymentProperties.setDomain("test-domain");
		this.deploymentProperties.setHealthCheck(ApplicationHealthCheck.NONE);
		this.deploymentProperties.setHost("test-host");
		this.deploymentProperties.setInstances(0);
		this.deploymentProperties.setMemory("0");

		givenRequestPushApplication(PushApplicationRequest.builder()
			.application(resource.getFile().toPath())
			.buildpack("test-buildpack")
			.diskQuota(0)
			.domain("test-domain")
			.healthCheckType(ApplicationHealthCheck.NONE)
			.host("test-host")
			.instances(0)
			.memory(0)
			.name("test-application-id")
			.noStart(true)
			.build(), Mono.empty());

		givenRequestUpdateApplication("test-application-id", Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"), Mono.empty());

		givenRequestBindService("test-application-id", "test-service-1", Mono.empty());
		givenRequestBindService("test-application-id", "test-service-2", Mono.empty());

		givenRequestStartApplication("test-application-id", null, null, Mono.empty());

		String deploymentId = this.deployer.deploy(new AppDeploymentRequest(
			new AppDefinition("test-application", Collections.emptyMap()),
			resource,
			Collections.emptyMap()));

		assertThat(deploymentId, equalTo("test-application-id"));

		verifyRequestUpdateApplication("test-application-id", Collections.singletonMap("SPRING_APPLICATION_JSON", "{}"));

		verifyRequestBindService("test-application-id", "test-service-1");
		verifyRequestBindService("test-application-id", "test-service-2");

		verifyRequestStartApplication("test-application-id", null, null);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void deployWithGroup() throws IOException {
		Resource resource = new FileSystemResource("src/test/resources/demo-0.0.1-SNAPSHOT.jar");

		given(this.applicationNameGenerator.generateAppName("test-group-test-application")).willReturn("test-group-test-application-id");

		givenRequestGetApplication("test-group-test-application-id",
			Mono.error(new IllegalArgumentException()),
			Mono.just(ApplicationDetail.builder()
				.diskQuota(0)
				.id("test-group-test-application-id")
				.instances(1)
				.memoryLimit(0)
				.name("test-group-test-application")
				.requestedState("RUNNING")
				.runningInstances(0)
				.stack("test-stack")
				.build()));

		givenRequestPushApplication(PushApplicationRequest.builder()
			.application(resource.getFile().toPath())
			.buildpack("https://github.com/cloudfoundry/java-buildpack.git")
			.diskQuota(1024)
			.instances(1)
			.memory(1024)
			.name("test-group-test-application-id")
			.noStart(true)
			.build(), Mono.empty());

		givenRequestUpdateApplication("test-group-test-application-id",
			FluentMap.<String, String>builder()
				.entry("SPRING_CLOUD_APPLICATION_GROUP", "test-group")
				.entry("SPRING_APPLICATION_JSON", "{}")
				.build(),
			Mono.empty());

		givenRequestBindService("test-group-test-application-id", "test-service-1", Mono.empty());
		givenRequestBindService("test-group-test-application-id", "test-service-2", Mono.empty());

		givenRequestStartApplication("test-group-test-application-id", null, null, Mono.empty());

		String deploymentId = this.deployer.deploy(new AppDeploymentRequest(
			new AppDefinition("test-application", Collections.emptyMap()),
			resource,
			Collections.singletonMap(GROUP_PROPERTY_KEY, "test-group")));

		assertThat(deploymentId, equalTo("test-group-test-application-id"));

		verifyRequestUpdateApplication("test-group-test-application-id",
			FluentMap.<String, String>builder()
				.entry("SPRING_CLOUD_APPLICATION_GROUP", "test-group")
				.entry("SPRING_APPLICATION_JSON", "{}")
				.build()
		);

		verifyRequestBindService("test-group-test-application-id", "test-service-1");
		verifyRequestBindService("test-group-test-application-id", "test-service-2");

		verifyRequestStartApplication("test-group-test-application-id", null, null);
	}

	@Before
	public void setUp() {
		MockitoAnnotations.initMocks(this);
		given(this.client.applicationsV2()).willReturn(this.applicationsV2);
		given(this.operations.applications()).willReturn(this.applications);
		given(this.operations.services()).willReturn(this.services);

		this.deploymentProperties.setServices(new HashSet<>(Arrays.asList("test-service-1", "test-service-2")));

		this.deployer = new CloudFoundryAppDeployer(this.applicationNameGenerator, this.client, this.deploymentProperties, this.operations);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusOfCrashedApplicationIsFailed() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("CRASHED")
				.build())
			.build()));

		AppStatus status = this.deployer.status("test-application-id");

		assertThat(status.getState(), equalTo(DeploymentState.failed));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusOfDownApplicationIsDeploying() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("DOWN")
				.build())
			.build()));

		AppStatus status = this.deployer.status("test-application-id");

		assertThat(status.getState(), equalTo(DeploymentState.deploying));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusOfFlappingApplicationIsDeployed() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("FLAPPING")
				.build())
			.build()));

		AppStatus status = deployer.status("test-application-id");

		assertThat(status.getState(), equalTo(DeploymentState.deployed));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusOfRunningApplicationIsDeployed() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("RUNNING")
				.build())
			.build()));

		AppStatus status = this.deployer.status("test-application-id");

		assertThat(status.getState(), equalTo(DeploymentState.deployed));
		assertThat(status.getInstances().get("test-application-0").toString(), equalTo("CloudFoundryAppInstanceStatus[test-application-0 : deployed]"));
		assertThat(status.getInstances().get("test-application-0").getAttributes(), equalTo(Collections.emptyMap()));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusOfStartingApplicationIsDeploying() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("STARTING")
				.build())
			.build()));

		AppStatus status = this.deployer.status("test-application-id");

		assertThat(status.getState(), equalTo(DeploymentState.deploying));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusOfUnknownApplicationIsUnknown() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("UNKNOWN")
				.build())
			.build()));

		AppStatus status = this.deployer.status("test-application-id");

		assertThat(status.getState(), equalTo(DeploymentState.unknown));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusWithAbnormalInstanceStateThrowsException() {
		givenRequestGetApplication("test-application-id", Mono.just(ApplicationDetail.builder()
			.diskQuota(0)
			.id("test-application-id")
			.instances(1)
			.memoryLimit(0)
			.name("test-application")
			.requestedState("RUNNING")
			.runningInstances(1)
			.stack("test-stack")
			.instanceDetail(InstanceDetail.builder()
				.state("ABNORMAL")
				.build())
			.build()));

		try {
			this.deployer.status("test-application-id").getState();
			Assert.fail();
		} catch (IllegalStateException e) {
			assertThat(e.getMessage(), containsString("Unsupported CF state"));
		}
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusWithFailingCAPICallRetries() throws Exception{
		AtomicInteger i = new AtomicInteger();
		Mono<ApplicationDetail> m = Mono.create(s -> {
			if (i.incrementAndGet() == 2) {
				s.success(ApplicationDetail.builder()
					.diskQuota(0)
					.id("test-application-id")
					.instances(1)
					.memoryLimit(0)
					.name("test-application")
					.requestedState("RUNNING")
					.runningInstances(1)
					.stack("test-stack")
					.instanceDetail(InstanceDetail.builder()
						.state("UNKNOWN")
						.build())
					.build());
			}
			else {
				s.error(new RuntimeException("Simulated Server Side error"));
			}
		});
		givenRequestGetApplication("test-application-id", m);

		DeploymentState state = this.deployer.status("test-application-id").getState();
		assertThat(state, is(DeploymentState.unknown));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusWithFailingCAPICallRetriesEventualError() throws Exception{
		AtomicInteger i = new AtomicInteger();
		Mono<ApplicationDetail> m = Mono.create(s -> {
			if (i.incrementAndGet() == 12) { // 12 is more than the number of retries
				s.success(ApplicationDetail.builder()
					.diskQuota(0)
					.id("test-application-id")
					.instances(1)
					.memoryLimit(0)
					.name("test-application")
					.requestedState("RUNNING")
					.runningInstances(1)
					.stack("test-stack")
					.instanceDetail(InstanceDetail.builder()
						.state("UNKNOWN")
						.build())
					.build());
			}
			else {
				s.error(new RuntimeException("Simulated Server Side error"));
			}
		});
		givenRequestGetApplication("test-application-id", m);
		this.deployer.deploymentProperties.setStatusTimeout(200); // Will cause wait of 20ms then 40ms,80ms

		DeploymentState state = this.deployer.status("test-application-id").getState();
		assertThat(state, is(DeploymentState.error));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void statusWithErrorThrownOnBlocking() throws Exception{
		AtomicInteger i = new AtomicInteger();
		Mono<ApplicationDetail> m = Mono.delay(Duration.ofSeconds(5)).then(
			Mono.create(s -> {
				i.incrementAndGet();
				s.success(ApplicationDetail.builder()
					.diskQuota(0)
					.id("test-application-id")
					.instances(1)
					.memoryLimit(0)
					.name("test-application")
					.requestedState("RUNNING")
					.runningInstances(1)
					.stack("test-stack")
					.instanceDetail(InstanceDetail.builder()
						.state("UNKNOWN")
						.build())
					.build());
		}));
		givenRequestGetApplication("test-application-id", m);
		this.deployer.deploymentProperties.setApiTimeout(1);// Is less than the delay() above

		DeploymentState state = this.deployer.status("test-application-id").getState();
		assertThat(state, is(DeploymentState.error));
		assertThat(i.get(), is(0));
	}

	@Test
	public void undeploy() {
		givenRequestDeleteApplication("test-application-id", Mono.empty());

		this.deployer.undeploy("test-application-id");

		verifyRequestDeleteApplication("test-application-id");
	}

	private void givenRequestBindService(String deploymentId, String service, Mono<Void> response) {
		given(this.operations.services()
			.bind(BindServiceInstanceRequest.builder()
				.applicationName(deploymentId)
				.serviceInstanceName(service)
				.build()))
			.willReturn(response);
	}

	private void givenRequestDeleteApplication(String id, Mono<Void> response) {
		given(this.operations.applications()
			.delete(DeleteApplicationRequest.builder()
				.deleteRoutes(true)
				.name(id)
				.build()))
			.willReturn(response);
	}

	@SuppressWarnings("unchecked")
	private void givenRequestGetApplication(String id, Mono<ApplicationDetail> response, Mono<ApplicationDetail>... responses) {
		given(this.operations.applications()
			.get(GetApplicationRequest.builder()
				.name(id)
				.build()))
			.willReturn(response, responses);
	}

	private void givenRequestPushApplication(PushApplicationRequest request, Mono<Void> response) {
		given(this.operations.applications()
			.push(request))
			.willReturn(response);
	}

	private void givenRequestStartApplication(String name, Duration stagingTimeout, Duration startupTimeout, Mono<Void> response) {
		given(this.operations.applications()
			.start(StartApplicationRequest.builder()
				.name(name)
				.stagingTimeout(stagingTimeout)
				.startupTimeout(startupTimeout)
				.build()))
			.willReturn(response);
	}

	private void givenRequestUpdateApplication(String applicationId, Map<String, String> environmentVariables, Mono<UpdateApplicationResponse> response) {
		given(this.client.applicationsV2()
			.update(UpdateApplicationRequest.builder()
				.applicationId(applicationId)
				.environmentJsons(environmentVariables)
				.build()))
			.willReturn(response);
	}

	private void verifyRequestBindService(String deploymentId, String service) {
		verify(this.operations.services())
			.bind(BindServiceInstanceRequest.builder()
				.applicationName(deploymentId)
				.serviceInstanceName(service)
				.build());
	}

	private void verifyRequestDeleteApplication(String id) {
		verify(this.operations.applications())
			.delete(DeleteApplicationRequest.builder()
				.deleteRoutes(true)
				.name(id)
				.build());
	}

	private void verifyRequestStartApplication(String name, Duration stagingTimeout, Duration startupTimeout) {
		verify(this.operations.applications())
			.start(StartApplicationRequest.builder()
				.name(name)
				.stagingTimeout(stagingTimeout)
				.startupTimeout(startupTimeout)
				.build());
	}

	private void verifyRequestUpdateApplication(String applicationId, Map<String, String> environmentVariables) {
		verify(this.client.applicationsV2())
			.update(UpdateApplicationRequest.builder()
				.applicationId(applicationId)
				.environmentJsons(environmentVariables)
				.build());
	}

}
