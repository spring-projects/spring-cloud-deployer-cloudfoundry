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

import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryConnectionProperties.CLOUDFOUNDRY_PROPERTIES;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.cloudfoundry.operations.applications.ApplicationHealthCheck;

import org.springframework.beans.factory.annotation.Value;

/**
 * Holds configuration properties for specifying what resources and services an app deployed to a Cloud Foundry runtime
 * will get.
 *
 * @author Eric Bottard
 * @author Greg Turnquist
 */
public class CloudFoundryDeploymentProperties {

	public static final String SERVICES_PROPERTY_KEY = CLOUDFOUNDRY_PROPERTIES + ".services";

	public static final String HEALTHCHECK_PROPERTY_KEY = CLOUDFOUNDRY_PROPERTIES + ".health-check";

	public static final String ROUTE_PATH_PROPERTY = CLOUDFOUNDRY_PROPERTIES + ".route-path";

	public static final String NO_ROUTE_PROPERTY = CLOUDFOUNDRY_PROPERTIES + ".no-route";

	public static final String HOST_PROPERTY = CLOUDFOUNDRY_PROPERTIES + ".host";

	public static final String DOMAIN_PROPERTY = CLOUDFOUNDRY_PROPERTIES + ".domain";

	public static final String BUILDPACK_PROPERTY_KEY = CLOUDFOUNDRY_PROPERTIES + ".buildpack";

	public static final String USE_SPRING_APPLICATION_JSON_KEY = CLOUDFOUNDRY_PROPERTIES + ".use-spring-application-json";

	/**
	 * The names of services to bind to all applications deployed as a module.
	 * This should typically contain a service capable of playing the role of a binding transport.
	 */
	private Set<String> services = new HashSet<>();

	/**
	 * The host name to use as part of the route. Defaults to hostname derived by Cloud Foundry.
	 */
	private String host = null;

	/**
	 * The domain to use when mapping routes for applications.
	 */
	private String domain;

	/**
	 * The buildpack to use for deploying the application.
	 */
	private String buildpack = "https://github.com/cloudfoundry/java-buildpack.git";

	/**
	 * The amount of memory to allocate, if not overridden per-app. Default unit is mebibytes, 'M' and 'G" suffixes supported.
	 */
	private String memory = "1024m";

	/**
	 * The amount of disk space to allocate, if not overridden per-app. Default unit is mebibytes, 'M' and 'G" suffixes supported.
	 */
	private String disk = "1024m";

	/**
	 * The type of health check to perform on deployed application, if not overridden per-app.
	 */
	private ApplicationHealthCheck healthCheck = null;

	/**
	 * The number of instances to run.
	 */
	private int instances = 1;

	/**
	 * Flag to enable prefixing the app name with a random prefix.
	 */
	private boolean enableRandomAppNamePrefix = true;

	/**
	 * Timeout for blocking API calls, in seconds.
	 */
	private long apiTimeout = 30L;



	/**
	 * Timeout for status API operations in milliseconds
	 */
	private long statusTimeout = 2_000L;

	/**
	 * Flag to indicate whether application properties are fed into SPRING_APPLICATION_JSON or ENVIRONMENT VARIABLES.
	 */
	private boolean useSpringApplicationJson = true;

	/**
	 * If set, override the timeout allocated for staging the app by the client.
	 */
	private Duration stagingTimeout;

	/**
	 * If set, override the timeout allocated for starting the app by the client.
	 */
	private Duration startupTimeout;

	/**
	 * String to use as prefix for name of deployed app.  Defaults to spring.application.name.
	 */
	@Value("${spring.application.name:}")
	private String appNamePrefix;

	public Set<String> getServices() {
		return services;
	}

	public void setServices(Set<String> services) {
		this.services = services;
	}

	public String getBuildpack() {
		return buildpack;
	}

	public void setBuildpack(String buildpack) {
		this.buildpack = buildpack;
	}

	public String getMemory() {
		return memory;
	}

	public void setMemory(String memory) {
		this.memory = memory;
	}

	public String getDisk() {
		return disk;
	}

	public void setDisk(String disk) {
		this.disk = disk;
	}

	public int getInstances() {
		return instances;
	}

	public void setInstances(int instances) {
		this.instances = instances;
	}

	public boolean isEnableRandomAppNamePrefix() {
		return enableRandomAppNamePrefix;
	}

	public void setEnableRandomAppNamePrefix(boolean enableRandomAppNamePrefix) {
		this.enableRandomAppNamePrefix = enableRandomAppNamePrefix;
	}

	public String getAppNamePrefix() {
		return appNamePrefix;
	}

	public void setAppNamePrefix(String appNamePrefix) {
		this.appNamePrefix = appNamePrefix;
	}

	public long getApiTimeout() {
		return apiTimeout;
	}

	public void setApiTimeout(long apiTimeout) {
		this.apiTimeout = apiTimeout;
	}

	public boolean isUseSpringApplicationJson() {
		return useSpringApplicationJson;
	}

	public void setUseSpringApplicationJson(boolean useSpringApplicationJson) {
		this.useSpringApplicationJson = useSpringApplicationJson;
	}

	public ApplicationHealthCheck getHealthCheck() {
		return healthCheck;
	}

	public void setHealthCheck(ApplicationHealthCheck healthCheck) {
		this.healthCheck = healthCheck;
	}

	public String getDomain() {
		return domain;
	}

	public void setDomain(String domain) {
		this.domain = domain;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public Duration getStagingTimeout() {
		return stagingTimeout;
	}

	public void setStagingTimeout(Duration stagingTimeout) {
		this.stagingTimeout = stagingTimeout;
	}

	public Duration getStartupTimeout() {
		return startupTimeout;
	}

	public void setStartupTimeout(Duration startupTimeout) {
		this.startupTimeout = startupTimeout;
	}
	public long getStatusTimeout() {
		return statusTimeout;
	}

	public void setStatusTimeout(long statusTimeout) {
		this.statusTimeout = statusTimeout;
	}
}
