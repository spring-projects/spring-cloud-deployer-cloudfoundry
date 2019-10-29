/*
 * Copyright 2016-2018 the original author or authors.
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.cloudfoundry.AbstractCloudFoundryException;
import org.cloudfoundry.UnknownCloudFoundryException;
import org.cloudfoundry.operations.services.BindServiceInstanceRequest;
import org.cloudfoundry.util.DelayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.core.RuntimeEnvironmentInfo;
import org.springframework.cloud.deployer.spi.util.ByteSizeUtils;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;

import org.springframework.util.FileSystemUtils;

import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.BUILDPACK_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.JAVA_OPTS_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY;

/**
 * Base class dealing with configuration overrides on a per-deployment basis, as well as common code for apps and tasks.
 *
 * @author Eric Bottard
 * @author Ilayaperumal Gopinathan
 */
class AbstractCloudFoundryDeployer {

	protected final RuntimeEnvironmentInfo runtimeEnvironmentInfo;

	final CloudFoundryDeploymentProperties deploymentProperties;

	private final Logger logger = LoggerFactory.getLogger(AbstractCloudFoundryDeployer.class);


	AbstractCloudFoundryDeployer(CloudFoundryDeploymentProperties deploymentProperties, RuntimeEnvironmentInfo runtimeEnvironmentInfo) {
		this.deploymentProperties = deploymentProperties;
		this.runtimeEnvironmentInfo = runtimeEnvironmentInfo;
	}

	int memory(AppDeploymentRequest request) {
		String withUnit = request.getDeploymentProperties()
			.getOrDefault(AppDeployer.MEMORY_PROPERTY_KEY, this.deploymentProperties.getMemory());
		return (int) ByteSizeUtils.parseToMebibytes(withUnit);
	}

	Set<String> servicesToBind(AppDeploymentRequest request) {

		Set<String> services = this.deploymentProperties.getServices().stream().filter(s->!ServiceParser
			.getServiceParameters(s).isPresent())
			.collect(Collectors.toSet());

		Set<String> requestServices = ServiceParser.splitServiceProperties(request.getDeploymentProperties().get
			(SERVICES_PROPERTY_KEY))
			.stream()
			.filter(s-> !ServiceParser.getServiceParameters(s).isPresent())
			.collect(Collectors.toSet());

		services.addAll(requestServices);
		return services;
	}

	boolean includesServiceParameters(AppDeploymentRequest request) {
		return
			this.deploymentProperties.getServices().stream()
				.anyMatch(s-> ServiceParser.getServiceParameters(s).isPresent()) ||
				ServiceParser.splitServiceProperties(request.getDeploymentProperties().get(SERVICES_PROPERTY_KEY)).stream()
					.anyMatch(s-> ServiceParser.getServiceParameters(s).isPresent());
	}

	Stream<BindServiceInstanceRequest> bindParameterizedServiceInstanceRequests(AppDeploymentRequest request,
		String deploymentId) {
		return ServiceParser.splitServiceProperties(request.getDeploymentProperties().get
			(SERVICES_PROPERTY_KEY)).stream()
			.filter(s-> ServiceParser.getServiceParameters(s).isPresent())
			.map(s->
				BindServiceInstanceRequest.builder()
					.applicationName(deploymentId)
					.serviceInstanceName(ServiceParser.getServiceInstanceName(s))
					.parameters(ServiceParser.getServiceParameters(s).get())
					.build()
			);
	}

	int diskQuota(AppDeploymentRequest request) {
		String withUnit = request.getDeploymentProperties()
			.getOrDefault(AppDeployer.DISK_PROPERTY_KEY, this.deploymentProperties.getDisk());
		return (int) ByteSizeUtils.parseToMebibytes(withUnit);
	}

	String buildpack(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(BUILDPACK_PROPERTY_KEY))
			.orElse(this.deploymentProperties.getBuildpack());
	}

	String javaOpts(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(JAVA_OPTS_PROPERTY_KEY))
				.orElse(this.deploymentProperties.getJavaOpts());
	}

	Predicate<Throwable> isNotFoundError() {
		return t -> t instanceof AbstractCloudFoundryException && ((AbstractCloudFoundryException) t).getStatusCode() == HttpStatus.NOT_FOUND.value();
	}

	/**
	 * Return a Docker image identifier if the application Resource is for a Docker image, or {@literal null} otherwise.
	 *
	 * @see #getApplication(AppDeploymentRequest)
	 */
	String getDockerImage(AppDeploymentRequest request) {
		try {
			String uri = request.getResource().getURI().toString();
			if (uri.startsWith("docker:")) {
				return uri.substring("docker:".length());
			} else {
				return null;
			}
		} catch (IOException e) {
			throw Exceptions.propagate(e);
		}
	}

	/**
	 * Return a Path to the application Resource or {@literal null} if the request is for a Docker image.
	 *
	 * @see #getDockerImage(AppDeploymentRequest)
	 */
	Path getApplication(AppDeploymentRequest request) {
		try {
			logger.info(
				"Preparing to push an application from {}" +
					". This may take some time if the artifact must be downloaded from a remote host.",
				request.getResource());
			if (!request.getResource().getURI().toString().startsWith("docker:")) {
				return request.getResource().getFile().toPath();
			} else {
				return null;
			}
		} catch (IOException e) {
			throw Exceptions.propagate(e);
		}
	}

	/**
	 * Return a function usable in {@literal doOnError} constructs that will unwrap unrecognized Cloud Foundry Exceptions
	 * and log the text payload.
	 */
	protected Consumer<Throwable> logError(String msg) {
		return e -> {
			if (e instanceof UnknownCloudFoundryException) {
				logger.error(msg + "\nUnknownCloudFoundryException encountered, whose payload follows:\n" + ((UnknownCloudFoundryException)e).getPayload(), e);
			} else {
				logger.error(msg, e);
			}
		};
	}


	/**
	 * To be used in order to retry the status operation for an application or task.
	 * @param id The application id or the task id
	 * @param <T> The type of status object being queried for, usually AppStatus or TaskStatus
	 * @return The function that executes the retry logic around for determining App or Task Status
	 */
	<T> Function<Mono<T>, Mono<T>> statusRetry(String id) {
		long statusTimeout = this.deploymentProperties.getStatusTimeout();
		long requestTimeout = Math.round(statusTimeout * 0.40); // wait 500ms with default status timeout of 2000ms
		long initialRetryDelay = Math.round(statusTimeout * 0.10); // wait 200ms with status timeout of 2000ms

		if (requestTimeout < 500L) {
			logger.info("Computed statusRetry Request timeout = {} ms is below 500ms minimum value.  Setting to 500ms", requestTimeout);
			requestTimeout = 500L;
		}
		final long requestTimeoutToUse = requestTimeout;
		return m -> m.timeout(Duration.ofMillis(requestTimeoutToUse))
			.doOnError(e -> logger.warn(String.format("Error getting status for %s within %sms, Retrying operation.", id, requestTimeoutToUse)))
			.retryWhen(DelayUtils.exponentialBackOffError(
				Duration.ofMillis(initialRetryDelay), //initial retry delay
				Duration.ofMillis(statusTimeout / 2), // max retry delay
				Duration.ofMillis(statusTimeout)) // max total retry time
				.andThen(retries -> Flux.from(retries).doOnComplete(() ->
					logger.info("Successfully retried getStatus operation status [{}] for {}", id))))
			.doOnError(e -> logger.error(String.format("Retry operation on getStatus failed for %s.  Max retry time %sms", id, statusTimeout)));
	}

	/**
	 * Always delete downloaded files for static http resources. Conditionally delete maven resources.
	 * @param appDeploymentRequest
	 */
	protected void deleteLocalApplicationResourceFile(AppDeploymentRequest appDeploymentRequest) {

		try {

			Optional<File> fileToDelete = fileToDelete(appDeploymentRequest.getResource());
			if (fileToDelete.isPresent()) {
				File applicationFile = fileToDelete.get();

				logger.info("Free Disk Space = {} bytes, Total Disk Space = {} bytes",
						applicationFile.getFreeSpace(),
						applicationFile.getTotalSpace());


				boolean deleted = deleteFileOrDirectory(applicationFile);
				logger.info((deleted) ? "Successfully deleted the application resource: " + applicationFile.getCanonicalPath() :
						"Could not delete the application resource: " + applicationFile.getCanonicalPath());
			}

		} catch(IOException e){
			logger.warn("Exception deleting the application resource after successful CF push request."
					+ " This could cause increase in disk space usage. Exception message: " + e.getMessage());
		}
	}

	/*
	 * Always delete files downloaded from http/s url.
	 * Delete maven resources if property is set.
	 */
	private Optional<File> fileToDelete(Resource resource) throws IOException {
		String scheme = resource.getURI().getScheme().toLowerCase();
		if (scheme.startsWith("http")) {
			return Optional.of(resource.getFile());
		}
		if (scheme.equals("maven") && deploymentProperties.isAutoDeleteMavenArtifacts()) {
			return Optional.of(resource.getFile().getParentFile());
		}
		return Optional.empty();
	}

	private boolean deleteFileOrDirectory(File fileToDelete) {
		boolean deleted;
		if (fileToDelete.isDirectory())
			deleted  = FileSystemUtils.deleteRecursively(fileToDelete);
		else {
			deleted = fileToDelete.delete();
		}
		return deleted;
	}

	public RuntimeEnvironmentInfo environmentInfo() {
		return runtimeEnvironmentInfo;
	}
}
