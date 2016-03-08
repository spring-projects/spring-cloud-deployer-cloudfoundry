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

import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;

/**
 * A deployer that targets Cloud Foundry using the public API.
 * @author Greg Turnquist
 */
public class CloudFoundryAppDeployer implements AppDeployer {


	private final CloudFoundryAppDeployProperties properties;

	private final CloudFoundryOperations operations;

	public CloudFoundryAppDeployer(CloudFoundryAppDeployProperties properties, CloudFoundryOperations operations) {
		this.properties = properties;
		this.operations = operations;
	}


	@Override
	public String deploy(AppDeploymentRequest request) {
		return null;
	}

	@Override
	public void undeploy(String id) {

	}

	@Override
	public AppStatus status(String id) {
		return operations.applications().get(GetApplicationRequest.builder().name(id).build())
				.then(ad -> createAppStatusBuilder(id, ad))
				.map(AppStatus.Builder::build)
				.get();
	}

	private Mono<AppStatus.Builder> createAppStatusBuilder(String id, ApplicationDetail ad) {
		return Mono.just(AppStatus.of(id))
				.then(b -> addInstances(b, ad));
	}

	private Mono<AppStatus.Builder> addInstances(AppStatus.Builder initial, ApplicationDetail ad) {
		return Flux.fromIterable(ad.getInstanceDetails())
				.zipWith(Flux.range(0, ad.getRunningInstances()))
				.reduce(initial, (b, acc) -> b.with(acc.));
	}
}
