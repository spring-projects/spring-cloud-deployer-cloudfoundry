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

import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static org.springframework.util.StringUtils.commaDelimitedListToSet;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.PushApplicationRequest;
import org.cloudfoundry.operations.applications.SetEnvironmentVariableApplicationRequest;
import org.cloudfoundry.operations.applications.StartApplicationRequest;
import org.cloudfoundry.operations.services.BindServiceRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;

/**
 * A deployer that targets Cloud Foundry using the public API.
 * 
 * @author Eric Bottard
 */
public class CloudFoundryAppDeployer implements AppDeployer {


	public static final String MEMORY_PROPERTY_KEY = "spring.cloud.deployer.cloudfoundry.memory";

	public static final String DISK_PROPERTY_KEY = "spring.cloud.deployer.cloudfoundry.disk";

	public static final String SERVICES_PROPERTY_KEY = "spring.cloud.deployer.cloudfoundry.services";

	private final CloudFoundryAppDeployProperties properties;

	private final CloudFoundryOperations operations;

	public CloudFoundryAppDeployer(CloudFoundryAppDeployProperties properties, CloudFoundryOperations operations) {
		this.properties = properties;
		this.operations = operations;
	}


	@Override
	public String deploy(AppDeploymentRequest request) {
		String name = request.getDefinition().getName();

		final InputStream inputStream;
		try {
			inputStream = request.getResource().getInputStream();
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		final String argsAsJson;
		try {
			argsAsJson = new ObjectMapper().writeValueAsString(request.getDefinition().getProperties());
		}
		catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}

		operations.applications()
				.push(PushApplicationRequest.builder()
						.name(name)
						.application(inputStream)
						.domain(properties.getDomain())
						.buildpack(properties.getBuildpack())
						.diskQuota(diskQuota(request))
						.instances(instances(request))
						.memory(memory(request))
						.noStart(true)
						.build())
				.after(() -> operations.applications().setEnvironmentVariable(
						SetEnvironmentVariableApplicationRequest.builder()
								.name(name)
								.variableName("SPRING_APPLICATION_JSON")
								.variableValue(argsAsJson)
								.build()
				))
				.after(() -> servicesToBind(request).flatMap(service -> operations.services().bind(BindServiceRequest.builder()
						.applicationName(name)
						.serviceName(service)
						.build())).after() /* this after() merges N Mono<Void> back into 1 */)
				.after(() -> operations.applications().start(StartApplicationRequest.builder().name(name).build()))
				.subscribe();


		return request.getDefinition().getName();
	}


	@Override
	public void undeploy(String id) {
		operations.applications().delete(
				DeleteApplicationRequest.builder().deleteRoutes(true).name(id).build()
		).subscribe();

	}

	@Override
	public AppStatus status(String id) {
		return operations.applications().get(GetApplicationRequest.builder().name(id).build())
				.then(ad -> createAppStatusBuilder(id, ad))
				.otherwise(e -> emptyAppStatusBuilder(id))
				.map(AppStatus.Builder::build)
				.get();
	}


	private Flux<String> servicesToBind(AppDeploymentRequest request) {
		Set<String> services = new HashSet<>(properties.getServices());
		services.addAll(commaDelimitedListToSet(request.getEnvironmentProperties().get(SERVICES_PROPERTY_KEY)));
		return Flux.fromIterable(services);
	}

	private int memory(AppDeploymentRequest request) {
		return parseInt(request.getEnvironmentProperties().getOrDefault(MEMORY_PROPERTY_KEY, valueOf(properties.getMemory())));
	}

	private int instances(AppDeploymentRequest request) {
		return parseInt(request.getEnvironmentProperties().getOrDefault(AppDeployer.COUNT_PROPERTY_KEY, "1"));
	}

	private int diskQuota(AppDeploymentRequest request) {
		return parseInt(request.getEnvironmentProperties().getOrDefault(DISK_PROPERTY_KEY, valueOf(properties.getDisk())));
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
