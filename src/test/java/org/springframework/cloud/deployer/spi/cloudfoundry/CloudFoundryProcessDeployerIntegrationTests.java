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


import java.net.URL;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.CloudFoundryOperationsBuilder;
import org.cloudfoundry.spring.client.SpringCloudFoundryClient;
import org.junit.Before;
import org.junit.ClassRule;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.IntegrationTest;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.test.AbstractAppDeployerIntegrationTests;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Integration tests for CloudFoundryProcessDeployerIntegration.
 * @author Eric Bottard
 */
@SpringApplicationConfiguration(classes = CloudFoundryProcessDeployerIntegrationTests.Config.class)
@IntegrationTest
public class CloudFoundryProcessDeployerIntegrationTests extends AbstractAppDeployerIntegrationTests {

	@ClassRule
	public static CloudFoundryTestSupport cfAvailable = new CloudFoundryTestSupport();

	@Autowired
	private AppDeployer appDeployer;

	@Override
	protected AppDeployer appDeployer() {
		return appDeployer;
	}

	/**
	 * Execution environments may override this default value to have tests wait longer for a deployment, for example if
	 * running in an environment that is known to be slow.
	 */
	protected double timeoutMultiplier = 1.0D;

	@Before
	public void init() {
		String multiplier = System.getenv("CF_DEPLOYER_TIMEOUT_MULTIPLIER");
		if (multiplier != null) {
			timeoutMultiplier = Double.parseDouble(multiplier);
		}
	}


	/**
	 * Return the timeout to use for repeatedly querying a module while it is being deployed.
	 * Default value is one minute, being queried every 5 seconds.
	 */
	@Override
	protected Timeout deploymentTimeout() {
		return new Timeout(12 * 4, (int) (5000 * timeoutMultiplier));
	}

	/**
	 * Return the timeout to use for repeatedly querying a module while it is being un-deployed.
	 * Default value is one minute, being queried every 5 seconds.
	 */
	@Autowired
	protected Timeout undeploymentTimeout() {
		return new Timeout(20 * 4, (int) (5000 * timeoutMultiplier));
	}


	@Configuration
	@EnableConfigurationProperties(CloudFoundryAppDeployProperties.class)
	public static class Config {

		@Bean
		public CloudFoundryClient cloudFoundryClient(CloudFoundryAppDeployProperties properties) {
			URL apiEndpoint = properties.getApiEndpoint();

			return SpringCloudFoundryClient.builder()
					.host(apiEndpoint.getHost())
					.port(apiEndpoint.getPort())
					.username(properties.getUsername())
					.password(properties.getPassword())
					.skipSslValidation(properties.isSkipSslValidation())
					.build();
		}

		@Bean
		CloudFoundryOperations cloudFoundryOperations(CloudFoundryAppDeployProperties properties, CloudFoundryClient cloudFoundryClient) {
			return new CloudFoundryOperationsBuilder()
					.cloudFoundryClient(cloudFoundryClient)
					.target(properties.getOrganization(), properties.getSpace())
					.build();
		}


		@Bean
		public AppDeployer appDeployer(CloudFoundryAppDeployProperties properties, CloudFoundryOperations operations) {
			return new CloudFoundryAppDeployer(properties, operations);
		}

	}

}
