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

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
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
import org.cloudfoundry.util.test.TestSubscriber;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
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

	CloudFoundryAppDeployer deployer;

	@Before
	public void setUp() {

		operations = mock(CloudFoundryOperations.class);
		client = mock(CloudFoundryClient.class);
		applications = mock(Applications.class);
		applicationsV2 = mock(ApplicationsV2.class);
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

}
