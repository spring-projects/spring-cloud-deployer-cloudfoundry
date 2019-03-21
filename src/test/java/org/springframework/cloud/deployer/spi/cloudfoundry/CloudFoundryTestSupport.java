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

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.deployer.spi.test.junit.AbstractExternalResourceTestSupport;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JUnit {@link org.junit.Rule} that detects the fact that a Cloud Foundry installation is available.
 *
 * @author Eric Bottard
 */
public class CloudFoundryTestSupport extends AbstractExternalResourceTestSupport<CloudFoundryClient> {

	private ConfigurableApplicationContext context;

	protected CloudFoundryTestSupport() {
		super("CLOUDFOUNDRY");
	}

	@Override
	protected void cleanupResource() throws Exception {
		context.close();
	}

	@Override
	protected void obtainResource() throws Exception {
		context = new SpringApplicationBuilder(Config.class).web(false).run();
		resource = context.getBean(CloudFoundryClient.class);
	}

	@Configuration
	@EnableConfigurationProperties
	public static class Config {

		@Bean
		@ConfigurationProperties(prefix = CloudFoundryConnectionProperties.CLOUDFOUNDRY_PROPERTIES)
		public CloudFoundryConnectionProperties cloudFoundryConnectionProperties() {
			return new CloudFoundryConnectionProperties();
		}

		@Bean
		public ConnectionContext connectionContext(CloudFoundryConnectionProperties properties) {
			return DefaultConnectionContext.builder()
					.apiHost(properties.getUrl().getHost())
					.skipSslValidation(properties.isSkipSslValidation())
					.build();
		}

		@Bean
		public TokenProvider tokenProvider(CloudFoundryConnectionProperties properties) {
			return PasswordGrantTokenProvider.builder()
					.username(properties.getUsername())
					.password(properties.getPassword())
					.build();
		}

		@Bean
		public CloudFoundryClient cloudFoundryClient(ConnectionContext connectionContext,
													 TokenProvider tokenProvider) {
			return ReactorCloudFoundryClient.builder()
					.connectionContext(connectionContext)
					.tokenProvider(tokenProvider)
					.build();
		}

		@Bean
		public CloudFoundryOperations cloudFoundryOperations(CloudFoundryClient cloudFoundryClient,
															 ConnectionContext connectionContext,
															 TokenProvider tokenProvider,
															 CloudFoundryConnectionProperties properties) {
			ReactorDopplerClient.builder()
				.connectionContext(connectionContext)
				.tokenProvider(tokenProvider)
				.build();

			ReactorUaaClient.builder()
				.connectionContext(connectionContext)
				.tokenProvider(tokenProvider)
				.build();

			return DefaultCloudFoundryOperations.builder()
				.cloudFoundryClient(cloudFoundryClient)
				.organization(properties.getOrg())
				.space(properties.getSpace())
				.build();
		}

	}
}
