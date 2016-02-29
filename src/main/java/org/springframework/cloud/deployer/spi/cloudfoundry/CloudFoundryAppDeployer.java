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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.cloudfoundry.client.lib.CloudFoundryOperations;
import org.cloudfoundry.client.lib.domain.InstancesInfo;
import org.cloudfoundry.client.lib.domain.Staging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.AppDeployer;
import org.springframework.cloud.deployer.spi.AppDeploymentId;
import org.springframework.cloud.deployer.spi.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.status.AppStatus;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * A deployer that targets Cloud Foundry using the public API.
 *
 * @author Greg Turnquist
 */
public class CloudFoundryAppDeployer implements AppDeployer {

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryAppDeployer.class);

	private CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties();

	private CloudFoundryOperations client;

	@Autowired
	public CloudFoundryAppDeployer(CloudFoundryAppDeployProperties properties,
								   CloudFoundryOperations client) {
		this.properties = properties;
		this.client = client;
	}

	/**
	 * Carry out the eqivalent of a "cf push".
	 *
	 * @param request the app deployment request
	 * @return
	 * @throws {@link IllegalStateException} is the app is already deployed
	 */
	@Override
	public AppDeploymentId deploy(AppDeploymentRequest request) {

		logger.info("Logging into " + properties.getOrganization() + "/" + properties.getSpace());
		this.client.login();

		// Pick app name
		String appName = request.getDefinition().getName();

		// Create application
		createApplication(appName, request);

		// Add env variables
		addEnvVariables(appName, request);

		// Upload application
		uploadApplication(appName, request);

		// Set number of instances
		client.updateApplicationInstances(appName, properties.getInstances());

		// Start application
		client.startApplication(appName);

		// Formulate the record of this deployment
		return new AppDeploymentId(request.getDefinition().getGroup(), appName);
	}

	/**
	 * Create/Update a Cloud Foundry application using various settings.
	 *
	 * TODO: Better handle URLs. Right now, it just creates a URL out of thin air based on app name
	 *
	 * @param appName
	 * @param request
	 */
	private void createApplication(String appName, AppDeploymentRequest request) {

		if (!appExists(appName)) {
			Staging staging = new Staging(null, properties.getBuildpack());
			String url = appName + "." + client.getDefaultDomain().getName();

			logger.info("Creating new application " + appName + " at " + url);

			client.createApplication(appName,
					staging,
					properties.getDisk(),
					properties.getMemory(),
					Collections.singletonList(url),
					new ArrayList<>(properties.getServices()));
		} else {
			throw new IllegalStateException(appName + " is already deployed.");
		}
	}

	/**
	 * Scan deploymentProperties and apply ones that start with the prefix as
	 * Cloud Foundry environmental variables minus the prefix.
	 *
	 * @param appName
	 * @param request
	 */
	private void addEnvVariables(String appName, AppDeploymentRequest request) {

		String envPrefix = "env.";

		Map<String,String> env = request.getDeploymentProperties().entrySet().stream()
				.filter(e -> e.getKey().startsWith(envPrefix))
				.collect(Collectors.toMap(
						e -> e.getKey().substring(envPrefix.length()),
						Map.Entry::getValue));

		logger.info("Assigning env variables " + env + " to " + appName);

		client.updateApplicationEnv(appName, env);
	}

	/**
	 * Fetch the {@Resource}'s {@InputStream} and upload it to Cloud Foundry
	 *
	 * @param appName
	 * @param request
	 */
	private void uploadApplication(String appName, AppDeploymentRequest request) {

		try {
			logger.info("Uploading " + request.getResource() + " to " + appName);

			client.uploadApplication(appName, "spring-cloud-deployer-cloudfoundry",
					request.getResource().getInputStream());
		} catch (IOException e) {
			throw new RuntimeException("Exception trying to deploy " + request, e);
		}
	}

	/**
	 * See if appName exists by trying to fetch it.
	 *
	 * @param appName
	 * @return
	 */
	private boolean appExists(String appName) {

		try {
			client.getApplication(appName);

			logger.info("Does " + appName + " exist? Yes");

			return true;
		} catch (HttpStatusCodeException e) {
			logger.info("Does " + appName + " exist? No");
			return false;
		}
	}

	/**
	 *
	 * @param id
	 * @throws {@link IllegalStateException} if the app does NOT exist.
	 */
	@Override
	public void undeploy(AppDeploymentId id) {

		if (appExists(id.getName())) {
			client.deleteApplication(id.getName());
		} else {
			throw new IllegalStateException(id.getName() + " is not deployed.");
		}
	}

	@Override
	public AppStatus status(AppDeploymentId id) {

		AppStatus.Builder builder = AppStatus.of(id);

		Optional.ofNullable(client.getApplicationInstances(id.getName()))
				.orElse(new InstancesInfo(Collections.emptyList()))
				.getInstances().stream()
				.map(i -> new CloudFoundryInstance(id, i, client))
				.forEach(i -> builder.with(i));

		return builder.build();
	}
}
