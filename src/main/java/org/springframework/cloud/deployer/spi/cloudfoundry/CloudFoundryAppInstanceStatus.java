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

import java.util.HashMap;
import java.util.Map;

import org.cloudfoundry.operations.applications.ApplicationDetail;

import org.springframework.cloud.deployer.spi.app.AppInstanceStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;

/**
 * Created by ericbottard on 09/03/16.
 */
public class CloudFoundryAppInstanceStatus implements AppInstanceStatus {

	private final ApplicationDetail.InstanceDetail instanceDetail;

	private final ApplicationDetail applicationDetail;

	private final int index;

	public CloudFoundryAppInstanceStatus(ApplicationDetail applicationDetail, ApplicationDetail.InstanceDetail instanceDetail, int index) {
		this.applicationDetail = applicationDetail;
		this.instanceDetail = instanceDetail;
		this.index = index;
	}

	@Override
	public String getId() {
		return applicationDetail.getName() + "-" + index;
	}

	@Override
	public DeploymentState getState() {
		if (instanceDetail == null) {
			return DeploymentState.failed;
		}
		switch (instanceDetail.getState()) {
			case "STARTING":
			case "DOWN":
				return DeploymentState.deploying;
			case "CRASHED":
				return DeploymentState.failed;
			// Seems the client incorrectly reports apps as FLAPPING when they are
			// obviously fine. Mapping as RUNNING for now
			case "FLAPPING":
			case "RUNNING":
				return DeploymentState.deployed;
			case "UNKNOWN":
				return DeploymentState.unknown;
			default:
				throw new IllegalStateException("Unsupported CF state " + instanceDetail.getState());
		}
	}

	@Override
	public Map<String, String> getAttributes() {
		return new HashMap<>(); // TODO
	}

	@Override
	public String toString() {
		return String.format("%s[%s : %s]" , getClass().getSimpleName(), getId(), getState());
	}
}
