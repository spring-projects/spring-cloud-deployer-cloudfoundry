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

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.rules.ExpectedException.none;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.ApplicationsV2;
import org.cloudfoundry.client.v2.applications.UpdateApplicationRequest;
import org.cloudfoundry.client.v2.applications.UpdateApplicationResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.Applications;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.StartApplicationRequest;
import org.cloudfoundry.operations.services.BindServiceInstanceRequest;
import org.cloudfoundry.operations.services.Services;
import org.cloudfoundry.util.test.TestSubscriber;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
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
 */
public class CloudFoundryAppDeployerTests {

	CloudFoundryOperations operations;

	CloudFoundryClient client;

	Applications applications;

	ApplicationsV2 applicationsV2;

	Services services;

	CloudFoundryAppDeployer deployer;

	@Rule public ExpectedException thrown = none();

	@Before
	public void setUp() {

		operations = mock(CloudFoundryOperations.class);
		client = mock(CloudFoundryClient.class);
		applications = mock(Applications.class);
		applicationsV2 = mock(ApplicationsV2.class);
		services = mock(Services.class);
		deployer = new CloudFoundryAppDeployer(new CloudFoundryDeployerProperties(), operations, client);
	}

	@Test
	public void shouldSwitchToSimpleDeploymentIdWhenGroupIsLeftOut() {

		// given
		given(operations.applications()).willReturn(applications);
		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder().build()));

		// when
		String deploymentId = deployer.deploy(new AppDeploymentRequest(
				new AppDefinition("test", Collections.emptyMap()),
				new FileSystemResource("")));

		// then
		assertThat(deploymentId, equalTo("test"));
	}

	@Test
	public void shouldNamespaceTheDeploymentIdWhenAGroupIsUsed() {

		// given
		given(operations.applications()).willReturn(applications);
		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder().build()));

		// when
		String deploymentId = deployer.deploy(new AppDeploymentRequest(
				new AppDefinition("test", Collections.emptyMap()),
				new FileSystemResource(""),
				Collections.singletonMap(AppDeployer.GROUP_PROPERTY_KEY, "prefix")));

		// then
		assertThat(deploymentId, equalTo("prefix-test"));
	}

	@Test
	public void moveAppPropertiesToSAJ() throws InterruptedException, JsonProcessingException {

		// given
		CloudFoundryDeployerProperties properties = new CloudFoundryDeployerProperties();

		// Define the env variables for the app
		Map<String, String> appDefinitionProperties = new HashMap<>();

		final String fooKey = "spring.cloud.foo";
		final String fooVal = "this should end up in SPRING_APPLICATION_JSON";

		final String barKey = "another.cloud.bar";
		final String barVal = "this should too";

		appDefinitionProperties.put(fooKey, fooVal);
		appDefinitionProperties.put(barKey, barVal);

		deployer = new CloudFoundryAppDeployer(properties, operations, client);

		given(operations.applications()).willReturn(applications);

		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder()
				.id("abc123")
				.build()));
		given(applications.push(any())).willReturn(Mono.empty());
		given(applications.start(any())).willReturn(Mono.empty());

		given(client.applicationsV2()).willReturn(applicationsV2);

		given(applicationsV2.update(any())).willReturn(Mono.just(UpdateApplicationResponse.builder()
				.build()));

		// when
		final TestSubscriber<Void> testSubscriber = new TestSubscriber<>();

		deployer.asyncDeploy(new AppDeploymentRequest(
				new AppDefinition("test", Collections.singletonMap("some.key", "someValue")),
				mock(Resource.class),
				appDefinitionProperties))
			.subscribe(testSubscriber);

		testSubscriber.verify(Duration.ofSeconds(10L));

		// then
		then(operations).should(times(3)).applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().push(any());
		then(applications).should().get(any());
		then(applications).should().start(any());
		verifyNoMoreInteractions(applications);

		then(client).should().applicationsV2();
		verifyNoMoreInteractions(client);

		then(applicationsV2).should().update(UpdateApplicationRequest.builder()
				.applicationId("abc123")
				.environmentJsons(new HashMap<String, String>() {{
					put("SPRING_APPLICATION_JSON",
							new ObjectMapper().writeValueAsString(
									Collections.singletonMap("some.key", "someValue")));
					put(fooKey, fooVal);
					put(barKey, barVal);
				}})
				.build());
		verifyNoMoreInteractions(applicationsV2);
	}

	@Test
	public void shouldHandleRoutineDeployment() throws InterruptedException, JsonProcessingException {

		// given
		deployer.getProperties().setServices(new HashSet<>(Arrays.asList("redis-service", "mysql-service")));

		given(operations.applications()).willReturn(applications);
		given(operations.services()).willReturn(services);

		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder()
				.id("abc123")
				.build()));
		given(applications.push(any())).willReturn(Mono.empty());
		given(applications.start(any())).willReturn(Mono.empty());

		given(client.applicationsV2()).willReturn(applicationsV2);

		given(applicationsV2.update(any())).willReturn(Mono.just(UpdateApplicationResponse.builder()
				.build()));

		given(services.bind(any())).willReturn(Mono.empty());

		// when
		final TestSubscriber<Void> testSubscriber = new TestSubscriber<>();

		deployer.asyncDeploy(new AppDeploymentRequest(
				new AppDefinition("test", Collections.emptyMap()),
				mock(Resource.class),
				Collections.emptyMap()))
				.subscribe(testSubscriber);

		testSubscriber.verify(Duration.ofSeconds(10L));

		// then
		then(operations).should(times(3)).applications();
		then(operations).should(times(2)).services();
		verifyNoMoreInteractions(operations);

		then(applications).should().push(any());
		then(applications).should().get(GetApplicationRequest.builder().name("test").build());
		then(applications).should().start(StartApplicationRequest.builder().name("test").build());
		verifyNoMoreInteractions(applications);

		then(client).should().applicationsV2();
		verifyNoMoreInteractions(client);

		then(applicationsV2).should().update(UpdateApplicationRequest.builder()
				.applicationId("abc123")
				.environmentJsons(new HashMap<String, String>() {{
					put("SPRING_APPLICATION_JSON", "{}");
				}})
				.build());
		verifyNoMoreInteractions(applicationsV2);

		then(services).should().bind(BindServiceInstanceRequest.builder()
				.applicationName("test")
				.serviceInstanceName("redis-service")
				.build());
		then(services).should().bind(BindServiceInstanceRequest.builder()
				.applicationName("test")
				.serviceInstanceName("mysql-service")
				.build());
		verifyNoMoreInteractions(services);
	}

	@Test
	public void shouldFailWhenDeployingSameAppTwice() throws InterruptedException, IllegalStateException {

		// given
		given(operations.applications()).willReturn(applications);

		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder()
			.id("abc123")
			.name("test")
			.runningInstances(1)
			.instanceDetail(ApplicationDetail.InstanceDetail.builder()
				.state("RUNNING")
				.build())
			.build()));

		thrown.expect(IllegalStateException.class);
		thrown.expectMessage(containsString("already deployed"));

		// when
		deployer.deploy(new AppDeploymentRequest(
				new AppDefinition("test", Collections.emptyMap()),
				mock(Resource.class),
				Collections.emptyMap()));

		// then
		// JUnit's exception handlers must be before the actual code is run
	}

	@Test
	public void shouldHandleRoutineUndeployment() throws InterruptedException, JsonProcessingException {

		// given
		given(operations.applications()).willReturn(applications);
		given(applications.delete(any())).willReturn(Mono.empty());

		// when
		final TestSubscriber<Void> testSubscriber = new TestSubscriber<>();

		deployer.asyncUndeploy("test")
			.subscribe(testSubscriber);

		testSubscriber.verify(Duration.ofSeconds(10L));

		// then
		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().delete(DeleteApplicationRequest.builder()
			.name("test")
			.deleteRoutes(true)
			.build());
		verifyNoMoreInteractions(applications);
	}

	@Test
	public void shouldReportDownStatus() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder()
				.id("abc123")
				.name("test")
				.runningInstances(1)
				.instanceDetail(ApplicationDetail.InstanceDetail.builder()
						.state("DOWN")
						.build())
				.build()));

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.deploying));

		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().get(any());
		verifyNoMoreInteractions(applications);
	}


	@Test
	public void shouldReportStartingStatus() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder()
				.id("abc123")
				.name("test")
				.runningInstances(1)
				.instanceDetail(ApplicationDetail.InstanceDetail.builder()
						.state("STARTING")
						.build())
				.build()));

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.deploying));

		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().get(any());
		verifyNoMoreInteractions(applications);
	}

	@Test
	public void shouldReportCrashedStatus() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder()
				.id("abc123")
				.name("test")
				.runningInstances(1)
				.instanceDetail(ApplicationDetail.InstanceDetail.builder()
						.state("CRASHED")
						.build())
				.build()));

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.failed));

		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().get(any());
		verifyNoMoreInteractions(applications);
	}

	@Test
	public void shouldReportFlappingStatus() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder()
				.id("abc123")
				.name("test")
				.runningInstances(1)
				.instanceDetail(ApplicationDetail.InstanceDetail.builder()
						.state("FLAPPING")
						.build())
				.build()));

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.deployed));

		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().get(any());
		verifyNoMoreInteractions(applications);
	}

	@Test
	public void shouldReportRunningStatus() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder()
				.id("abc123")
				.name("test")
				.runningInstances(1)
				.instanceDetail(ApplicationDetail.InstanceDetail.builder()
						.state("RUNNING")
						.build())
				.build()));

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.deployed));
		assertThat(status.getInstances().get("test-0").toString(), equalTo("CloudFoundryAppInstanceStatus[test-0 : deployed]"));
		assertThat(status.getInstances().get("test-0").getAttributes(), equalTo(Collections.emptyMap()));

		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().get(any());
		verifyNoMoreInteractions(applications);
	}

	@Test
	public void shouldReportUnknownStatus() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder()
				.id("abc123")
				.name("test")
				.runningInstances(1)
				.instanceDetail(ApplicationDetail.InstanceDetail.builder()
						.state("UNKNOWN")
						.build())
				.build()));

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.unknown));

		then(operations).should().applications();
		verifyNoMoreInteractions(operations);

		then(applications).should().get(any());
		verifyNoMoreInteractions(applications);
	}

	@Test
	public void shouldErrorOnUnknownState() throws InterruptedException {

		// given
		given(operations.applications()).willReturn(applications);

		given(applications.get(any())).willReturn(Mono.just(ApplicationDetail.builder()
				.id("abc123")
				.name("test")
				.runningInstances(1)
				.instanceDetail(ApplicationDetail.InstanceDetail.builder()
						.state("some code never before seen")
						.build())
				.build()));

		thrown.expect(IllegalStateException.class);
		thrown.expectMessage(containsString("Unsupported CF state"));

		// when
		AppStatus status = deployer.status("test");

		// then
		assertThat(status.getState(), equalTo(DeploymentState.unknown));
	}

}