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

import java.util.LinkedHashMap;
import java.util.Map;

import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.InstanceDetail;

import org.springframework.cloud.deployer.spi.app.AppInstanceStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;

/**
 * Maps status returned by the Cloud Foundry API to {@link AppInstanceStatus}.
 *
 * @author Eric Bottard
 */
public class CloudFoundryAppInstanceStatus implements AppInstanceStatus {

	private final InstanceDetail instanceDetail;

	private final ApplicationDetail applicationDetail;

	private final int index;

	public CloudFoundryAppInstanceStatus(ApplicationDetail applicationDetail, InstanceDetail instanceDetail, int index) {
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
				throw new IllegalStateException("Unsupported CF state: " + instanceDetail.getState());
		}
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attributes = new LinkedHashMap<>();
		if (instanceDetail != null) {
			if (instanceDetail.getCpu() != null) {
				attributes.put("cpu", String.format("%.1f%%", instanceDetail.getCpu() * 100d));
			}
			if (instanceDetail.getDiskQuota() != null && instanceDetail.getDiskUsage() != null) {
				attributes.put("disk", String.format("%.1f%%", 100d * instanceDetail.getDiskUsage() / instanceDetail.getDiskQuota()));
			}
			if (instanceDetail.getMemoryQuota() != null && instanceDetail.getMemoryUsage() != null) {
				attributes.put("memory", String.format("%.1f%%", 100d * instanceDetail.getMemoryUsage() / instanceDetail.getMemoryQuota()));
			}
		}
		return attributes;
	}

	@Override
	public String toString() {
		return String.format("%s[%s : %s]" , getClass().getSimpleName(), getId(), getState());
	}
}
