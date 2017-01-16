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

import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryConnectionProperties.CLOUDFOUNDRY_PROPERTIES;

import java.time.Duration;

import com.github.zafarkhaja.semver.Version;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.info.GetInfoRequest;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Creates a {@link CloudFoundryAppDeployer}
 *
 * @author Eric Bottard
 * @author Ben Hale
 */
@Configuration
@EnableConfigurationProperties
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
public class CloudFoundryDeployerAutoConfiguration {

	private static final Version CF_TASKS_INFLECTION_POINT = Version.forIntegers(2, 63, 0);

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryDeployerAutoConfiguration.class);

	@Bean
	@ConditionalOnMissingBean(name = "appDeploymentProperties")
	public CloudFoundryDeploymentProperties appDeploymentProperties() {
		return defaultSharedDeploymentProperties();
	}

	@Bean
	@ConditionalOnMissingBean(name = "taskDeploymentProperties")
	public CloudFoundryDeploymentProperties taskDeploymentProperties() {
		return defaultSharedDeploymentProperties();
	}

	@Bean
	@ConfigurationProperties(prefix = CLOUDFOUNDRY_PROPERTIES)
	public CloudFoundryDeploymentProperties defaultSharedDeploymentProperties() {
		return new CloudFoundryDeploymentProperties();
	}

	@Bean
	@ConditionalOnMissingBean
	@ConfigurationProperties(prefix = CloudFoundryConnectionProperties.CLOUDFOUNDRY_PROPERTIES)
	public CloudFoundryConnectionProperties cloudFoundryConnectionProperties() {
		return new CloudFoundryConnectionProperties();
	}

	@Bean
	@ConditionalOnMissingBean
	public ConnectionContext connectionContext(CloudFoundryConnectionProperties properties) {
		return DefaultConnectionContext.builder()
				.apiHost(properties.getUrl().getHost())
				.skipSslValidation(properties.isSkipSslValidation())
				.build();
	}

	@Bean
	@ConditionalOnMissingBean
	public TokenProvider tokenProvider(CloudFoundryConnectionProperties properties) {
		return PasswordGrantTokenProvider.builder()
				.username(properties.getUsername())
				.password(properties.getPassword())
				.build();
	}

	@Bean
	@ConditionalOnMissingBean
	public CloudFoundryClient cloudFoundryClient(ConnectionContext connectionContext, TokenProvider tokenProvider) {
		return ReactorCloudFoundryClient.builder()
				.connectionContext(connectionContext)
				.tokenProvider(tokenProvider)
				.build();
	}

	@Bean
	@ConditionalOnMissingBean
	public CloudFoundryOperations cloudFoundryOperations(CloudFoundryClient cloudFoundryClient, CloudFoundryConnectionProperties properties) {
		return DefaultCloudFoundryOperations.builder()
			.cloudFoundryClient(cloudFoundryClient)
			.organization(properties.getOrg())
			.space(properties.getSpace())
			.build();
	}

	@Bean
	@ConditionalOnMissingBean(AppDeployer.class)
	public AppDeployer appDeployer(CloudFoundryOperations operations, CloudFoundryClient client, AppNameGenerator applicationNameGenerator) {
		return new CloudFoundryAppDeployer(applicationNameGenerator, client, appDeploymentProperties(), operations);
	}

	@Bean
	@ConditionalOnMissingBean(AppNameGenerator.class)
	public AppNameGenerator appDeploymentCustomizer() {
		return new CloudFoundryAppNameGenerator(appDeploymentProperties());
	}

	@Bean
	@ConditionalOnMissingBean(TaskLauncher.class)
	public TaskLauncher taskLauncher(CloudFoundryClient client, CloudFoundryConnectionProperties connectionProperties, CloudFoundryOperations operations) {
		return client.info()
			.get(GetInfoRequest.builder()
				.build())
			.map(response -> Version.valueOf(response.getApiVersion()))
			.doOnNext(version -> logger.info("Connecting to Cloud Foundry with API Version {}", version))
			.map(version -> {
				if (version.greaterThanOrEqualTo(CF_TASKS_INFLECTION_POINT)) {
					return new CloudFoundry2630AndLaterTaskLauncher(client, taskDeploymentProperties(), operations);
				} else {
					return new CloudFoundry2620AndEarlierTaskLauncher(client, taskDeploymentProperties(), operations, connectionProperties.getSpace());
				}
			})
			.block(Duration.ofSeconds(taskDeploymentProperties().getApiTimeout()));
	}

	@Bean
	@ConfigurationPropertiesBinding
	public DurationConverter durationConverter() {
		return new DurationConverter();
	}

}
