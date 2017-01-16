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

import java.time.Duration;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v3.tasks.CancelTaskRequest;
import org.cloudfoundry.client.v3.tasks.CancelTaskResponse;
import org.cloudfoundry.client.v3.tasks.GetTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;

/**
 * Abstract class to provide base functionality for launching Tasks on Cloud Foundry.
 * This class provides the base SPI for  {@link CloudFoundry2620AndEarlierTaskLauncher}
 * and {@link CloudFoundry2630AndLaterTaskLauncher}.
 *
 * Does not override the default no-op implementation for {@link TaskLauncher#cleanup(String)}
 * and {@link TaskLauncher#destroy(String)}.
 */
abstract class AbstractCloudFoundryTaskLauncher extends AbstractCloudFoundryDeployer implements TaskLauncher {

	private static final Logger logger = LoggerFactory.getLogger(AbstractCloudFoundryTaskLauncher.class);

	private final CloudFoundryClient client;

	AbstractCloudFoundryTaskLauncher(CloudFoundryClient client, CloudFoundryDeploymentProperties deploymentProperties) {
		super(deploymentProperties);
		this.client = client;
	}

	/**
	 * Setup a reactor flow to cancel a running task.  This implementation opts to be asynchronous.
	 *
	 * @param id the task's id to be canceled as returned from the {@link TaskLauncher#launch(AppDeploymentRequest)}
	 */
	@Override
	public void cancel(String id) {
		requestCancelTask(id)
			.timeout(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()))
			.doOnSuccess(r -> logger.info("Task {} cancellation successful", id))
			.doOnError(t -> logger.error(String.format("Task %s cancellation failed", id), t))
			.subscribe();
	}

	/**
	 * Lookup the current status based on task id.
	 *
	 * @param id taskId as returned from the {@link TaskLauncher#launch(AppDeploymentRequest)}
	 * @return the current task status
	 */
	@Override
	public TaskStatus status(String id) {
		return requestGetTask(id)
			.map(this::toTaskStatus)
			.otherwiseReturn(isNotFoundError(),
				new TaskStatus(id, LaunchState.unknown, null))
			.doOnSuccess(r -> logger.info("Task {} status successful", id))
			.doOnError(t -> logger.error(String.format("Task %s status failed", id), t))
			.block(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()));
	}

	protected TaskStatus toTaskStatus(GetTaskResponse response) {
		switch (response.getState()) {
			case SUCCEEDED_STATE:
				return new TaskStatus(response.getId(), LaunchState.complete, null);
			case RUNNING_STATE:
				return new TaskStatus(response.getId(), LaunchState.running, null);
			case PENDING_STATE:
				return new TaskStatus(response.getId(), LaunchState.launching, null);
			case CANCELING_STATE:
				return new TaskStatus(response.getId(), LaunchState.cancelled, null);
			case FAILED_STATE:
				return new TaskStatus(response.getId(), LaunchState.failed, null);
			default:
				throw new IllegalStateException(String.format("Unsupported CF task state %s", response.getState()));
		}
	}

	private Mono<CancelTaskResponse> requestCancelTask(String taskId) {
		return this.client.tasks()
			.cancel(CancelTaskRequest.builder()
				.taskId(taskId)
				.build());
	}

	private Mono<GetTaskResponse> requestGetTask(String taskId) {
		return this.client.tasks()
			.get(GetTaskRequest.builder()
				.taskId(taskId)
				.build());
	}

	@Override
	public void cleanup(String id) {

	}

	@Override
	public void destroy(String appName) {

	}
}
