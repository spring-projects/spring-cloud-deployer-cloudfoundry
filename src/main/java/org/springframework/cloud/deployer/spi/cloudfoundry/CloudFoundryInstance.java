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

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.cloudfoundry.client.lib.CloudFoundryOperations;
import org.cloudfoundry.client.lib.domain.InstanceInfo;
import org.cloudfoundry.client.lib.domain.InstanceState;
import org.cloudfoundry.client.lib.domain.InstancesInfo;

import org.springframework.cloud.deployer.spi.app.AppInstanceStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * Wrap an {@link InstanceInfo} and provide convenient operations.
 *
 * @author Greg Turnquist
 */
public class CloudFoundryInstance implements AppInstanceStatus {

	private final String appDeploymentId;

	private final InstanceInfo instance;
	private final CloudFoundryOperations client;

	public CloudFoundryInstance(String appDeploymentId, InstanceInfo instance, CloudFoundryOperations client) {

		this.appDeploymentId = appDeploymentId;
		this.instance = instance;
		this.client = client;
	}

	@Override
	public String getId() {
		return appDeploymentId + "(" + instance.getIndex() + ")";
	}

	/**
	 * Parse the current state of an app deployed to Cloud Foundry
	 *
	 * @return {@link DeploymentState} containing list of Cloud Foundry instances
	 */
	@Override
	public DeploymentState getState() {

		try {
			return Optional.ofNullable(client.getApplicationInstances(appDeploymentId))
						.orElse(new InstancesInfo(Collections.emptyList()))
						.getInstances().stream()
							.filter(i -> i.getIndex() == this.instance.getIndex())
							.map(i -> getDeploymentState(i.getState()))
							.findFirst()
							.orElse(DeploymentState.unknown);

		} catch (HttpStatusCodeException e) {
			// Failing to look up an app indicates failure to deploy
			return DeploymentState.failed;
		}
	}

	/**
	 * Convert from Cloud Foundry {@link InstanceState} to Spring Cloud Deployer {@link DeploymentState}.
	 *
	 * @param instanceState
	 * @return {@link DeploymentState} for a single Cloud Foundry instance
	 */
	private DeploymentState getDeploymentState(InstanceState instanceState) {

		switch (instanceState == null? InstanceState.UNKNOWN : instanceState) {
			case STARTING:
				return DeploymentState.deploying;
			case RUNNING:
				return DeploymentState.deployed;
			case FLAPPING:
				return DeploymentState.unknown;
			case CRASHED:
				return DeploymentState.failed;
			case DOWN:
				return DeploymentState.failed;
			case UNKNOWN:
			default:
				return DeploymentState.unknown;
		}
	}

	/**
	 * Grab the application's environment variables from Cloud Foundry
	 *
	 * @return {@link Map} of environment variables read from Cloud Foundry app
	 */
	@Override
	public Map<String, String> getAttributes() {

		return client.getApplicationEnvironment(appDeploymentId)
				.entrySet().stream()
				.collect(Collectors.toMap(
					Map.Entry::getKey,
					value -> value.getValue().toString()));
	}
}
