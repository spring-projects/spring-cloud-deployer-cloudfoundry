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
package org.springframework.cloud.deployer.spi.cloudfoundry.v1;

import org.cloudfoundry.client.lib.CloudFoundryOperations;
import org.cloudfoundry.client.lib.domain.InstancesInfo;
import org.cloudfoundry.client.lib.domain.Staging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryAppDeployProperties;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryInstance;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.web.client.HttpStatusCodeException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.joining;

/**
 * A deployer that targets Cloud Foundry using the public API.
 *
 * @author Greg Turnquist
 */
public class CloudFoundryAppDeployer implements AppDeployer {

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryAppDeployer.class);

	private CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties();

	private CloudFoundryOperations client;

	private List<Logger> loggers = new ArrayList<>();

	@Autowired
	public CloudFoundryAppDeployer(CloudFoundryAppDeployProperties properties,
								   CloudFoundryOperations client) {
		this.properties = properties;
		this.client = client;
		this.registerCustomerLogger(logger);
	}

	public void registerCustomerLogger(Logger logger) {
		this.loggers.add(logger);
	}

	/**
	 * Carry out the eqivalent of a "cf push".
	 *
	 * @param request the app deployment request
	 * @return
	 * @throws {@link IllegalStateException} is the app is already deployed
	 */
	@Override
	public String deploy(AppDeploymentRequest request) {

		String appName = request.getDefinition().getName();

		createApplication(appName);

		addEnvVariables(appName, request);

		uploadApplication(appName, request);

		loggers.parallelStream().forEach(logger -> logger.info("Scaling " + appName + " to " + properties.getInstances() + " instance" + (properties.getInstances() == 1 ? "" : "s")));
		client.updateApplicationInstances(appName, properties.getInstances());

		loggers.parallelStream().forEach(logger -> logger.info("Starting " + appName));
		client.startApplication(appName);

		return appName;
	}

	/**
	 * Create/Update a Cloud Foundry application using various settings.
	 *
	 * TODO: Better handle URLs. Right now, it just creates a URL out of thin air based on app name
	 *
	 * @param appName
	 */
	private void createApplication(String appName) {

		if (!appExists(appName)) {
			Staging staging = new Staging(null, properties.getBuildpack());
			String url = appName + "." + client.getDefaultDomain().getName();

			loggers.parallelStream().forEach(logger -> logger.info("Creating new application " + appName + " at " + url + " with "
					+ Arrays.asList(
						properties.getMemory() + "M mem",
						properties.getDisk() + "M disk",
						"[" + properties.getServices().stream().collect(joining(",")) + "] services",
						!properties.getBuildpack().equals("") ? properties.getBuildpack() + " buildpack" : "default buildpack"
					).stream().collect(joining(", "))));

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

		Map<String,String> env = request.getEnvironmentProperties().entrySet().stream()
				.filter(e -> e.getKey().startsWith(envPrefix))
				.collect(Collectors.toMap(
						e -> e.getKey().substring(envPrefix.length()),
						Map.Entry::getValue));

		loggers.parallelStream().forEach(logger -> logger.info("Assigning env variables " + env + " to " + appName));

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
			loggers.parallelStream().forEach(logger -> logger.info("Uploading " + request.getResource() + " to " + appName));

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
	 * @return boolean state of whether or not the app exists in Cloud Foundry
	 */
	private boolean appExists(String appName) {

		try {
			client.getApplication(appName);

			loggers.parallelStream().forEach(logger -> logger.info("Does " + appName + " exist? Yes"));

			return true;
		} catch (HttpStatusCodeException e) {
			loggers.parallelStream().forEach(logger -> logger.info("Does " + appName + " exist? No"));
			return false;
		}
	}

	/**
	 *
	 * @param id
	 * @throws {@link IllegalStateException} if the app does NOT exist.
	 */
	@Override
	public void undeploy(String id) {

		if (appExists(id)) {
			client.deleteApplication(id);
		} else {
			throw new IllegalStateException(id + " is not deployed.");
		}
	}

	@Override
	public AppStatus status(String id) {

		AppStatus.Builder builder = AppStatus.of(id);

		Optional.ofNullable(client.getApplicationInstances(id))
				.orElse(new InstancesInfo(Collections.emptyList()))
				.getInstances().stream()
				.map(i -> new CloudFoundryInstance(id, i, client))
				.forEach(i -> builder.with(i));

		return builder.build();
	}

}
