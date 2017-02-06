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

import java.util.LinkedHashMap;
import java.util.Map;

import org.cloudfoundry.client.lib.domain.CloudApplication;
import org.springframework.cloud.deployer.spi.app.AppInstanceStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;

/**
 * Maps status returned by the Cloud Foundry API to {@link AppInstanceStatus}.
 *
 * @author Eric Bottard
 */
public class CloudFoundryV1AppInstanceStatus implements AppInstanceStatus {

	private final CloudApplication cloudApplication;

	public CloudFoundryV1AppInstanceStatus(CloudApplication cloudApplication) {
		this.cloudApplication = cloudApplication;
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attributes = new LinkedHashMap<>();
		if (cloudApplication != null) {
			// MLP CPU is available on InstanceStats.  Note this method is only used in tests, and compares to an empty map....?
//			if (cloudApplication.getCpu() != null) {
//				attributes.put("cpu", String.format("%.1f%%", cloudApplication.getCpu() * 100d));
//			}
//			if (cloudApplication.getDiskQuota() != null && cloudApplication.getDiskUsage() != null) {
//				attributes.put("disk", String.format("%.1f%%", 100d * instanceDetail.getDiskUsage() / instanceDetail.getDiskQuota()));
//			}
//			if (instanceDetail.getMemoryQuota() != null && instanceDetail.getMemoryUsage() != null) {
//				attributes.put("memory", String.format("%.1f%%", 100d * instanceDetail.getMemoryUsage() / instanceDetail.getMemoryQuota()));
//			}
		}
		return attributes;
	}

	@Override
	public String getId() {
		return cloudApplication.getName();
	}

	@Override
	public DeploymentState getState() {
		if (this.cloudApplication == null) {
			return DeploymentState.failed;
		}
		switch (cloudApplication.getState().name()) {

//			java.lang.IllegalStateException: Unsupported CF state: STOPPED
//			at org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryV1AppInstanceStatus.getState(CloudFoundryV1AppInstanceStatus.java:82)
//			at org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryV1AppDeployer.status(CloudFoundryV1AppDeployer.java:119)
//			at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
//			at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62)
//			at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
//			at java.lang.reflect.Method.invoke(Method.java:498)


			/* 	public enum AppState {
		UPDATING, STARTED, STOPPED
	}*/
			case "UPDATING":
				return DeploymentState.deploying;
			case "STARTED":
				return DeploymentState.deployed;
			case "STOPPED":
				return DeploymentState.partial;  // what to map?
			default:
				throw new IllegalStateException("Unsupported CF state: " + cloudApplication.getState().name());

			// Individual app state
//			case "STARTING":
//			case "DOWN":
//				return DeploymentState.deploying;
//			case "CRASHED":
//				return DeploymentState.failed;
//			// Seems the client incorrectly reports apps as FLAPPING when they are
//			// obviously fine. Mapping as RUNNING for now
//			case "FLAPPING":
//			case "RUNNING":
//				return DeploymentState.deployed;
//			case "UNKNOWN":
//				return DeploymentState.unknown;
//			default:
//				throw new IllegalStateException("Unsupported CF state: " + cloudApplication.getState().name());
		}
	}

	@Override
	public String toString() {
		return String.format("%s[%s : %s]", getClass().getSimpleName(), getId(), getState());
	}
}
