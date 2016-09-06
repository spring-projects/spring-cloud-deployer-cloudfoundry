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

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.doppler.ReactorDopplerClient;
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider;
import org.cloudfoundry.reactor.uaa.ReactorUaaClient;

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
 */
@Configuration
@EnableConfigurationProperties(CloudFoundryConnectionProperties.class)
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
public class CloudFoundryDeployerAutoConfiguration {

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
	public CloudFoundryOperations cloudFoundryOperations(CloudFoundryClient cloudFoundryClient,
														 ConnectionContext connectionContext,
														 TokenProvider tokenProvider,
														 CloudFoundryConnectionProperties properties) {
		ReactorDopplerClient dopplerClient = ReactorDopplerClient.builder()
				.connectionContext(connectionContext)
				.tokenProvider(tokenProvider)
				.build();

		ReactorUaaClient uaaClient = ReactorUaaClient.builder()
				.connectionContext(connectionContext)
				.tokenProvider(tokenProvider)
				.build();

		return DefaultCloudFoundryOperations.builder()
			.cloudFoundryClient(cloudFoundryClient)
			.dopplerClient(dopplerClient)
			.uaaClient(uaaClient)
			.organization(properties.getOrg())
			.space(properties.getSpace())
			.build();
	}


	@Bean
	@ConditionalOnMissingBean(AppDeployer.class)
	public AppDeployer appDeployer(CloudFoundryConnectionProperties connectionProperties, CloudFoundryOperations operations, CloudFoundryClient client,
								   AppNameGenerator appDeploymentCustomizer) {
		return new CloudFoundryAppDeployer(connectionProperties, appDeploymentProperties(), operations, client, appDeploymentCustomizer);
	}

	@Bean
	@ConditionalOnMissingBean(AppNameGenerator.class)
	public AppNameGenerator appDeploymentCustomizer() {
		return new CloudFoundryAppNameGenerator(appDeploymentProperties(), new WordListRandomWords());
	}

	@Bean
	@ConditionalOnMissingBean(TaskLauncher.class)
	public TaskLauncher taskLauncher(CloudFoundryClient client, CloudFoundryConnectionProperties connectionProperties, CloudFoundryOperations operations) {
		return new CloudFoundryTaskLauncher(client, operations, connectionProperties, taskDeploymentProperties());
	}

	@Bean
	@ConfigurationPropertiesBinding
	public DurationConverter durationConverter() {
		return new DurationConverter();
	}
}
