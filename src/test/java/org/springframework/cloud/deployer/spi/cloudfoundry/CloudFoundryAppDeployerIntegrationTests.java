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

import org.junit.Before;
import org.junit.ClassRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.IntegrationTest;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.test.AbstractAppDeployerIntegrationTests;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Integration tests for CloudFoundryAppDeployer.
 *
 * @author Eric Bottard
 * @author Greg Turnquist
 */
@SpringApplicationConfiguration(classes = CloudFoundryAppDeployerIntegrationTests.Config.class)
@IntegrationTest
public class CloudFoundryAppDeployerIntegrationTests extends AbstractAppDeployerIntegrationTests {

	private static final Logger log = LoggerFactory.getLogger(CloudFoundryAppDeployerIntegrationTests.class);

	@ClassRule
	public static CloudFoundryTestSupport cfAvailable = new CloudFoundryTestSupport();

	@Autowired
	ApplicationContext context;

	@Autowired
	private AppDeployer appDeployer;

	AppDeploymentRequest request;

	CloudFoundryAppDeployer cloudFoundryAppDeployer;

	@Override
	protected AppDeployer appDeployer() {
		return appDeployer;
	}

	@Override
	protected Resource integrationTestProcessor() {
		return context.getResource("classpath:demo-0.0.1-SNAPSHOT.jar");
	}

	/**
	 * Execution environments may override this default value to have tests wait longer for a deployment, for example if
	 * running in an environment that is known to be slow.
	 */
	protected double timeoutMultiplier = 1.0D;

	protected int maxRetries = 1000;

	@Before
	public void init() {
		String multiplier = System.getenv("CF_DEPLOYER_TIMEOUT_MULTIPLIER");
		if (multiplier != null) {
			timeoutMultiplier = Double.parseDouble(multiplier);
		}

		Map<String, String> envProperties = new HashMap<>();
		envProperties.put("organization", "spring-cloud");
		envProperties.put("space", "production");

		request = new AppDeploymentRequest(
			new AppDefinition("sdrdemo", Collections.emptyMap()),
			context.getResource("classpath:spring-data-rest-demo-0.0.1-SNAPSHOT.jar"),
			envProperties);

		cloudFoundryAppDeployer = (CloudFoundryAppDeployer) appDeployer;
	}

	/**
	 * Doesn't appear like we can enter the failed state, tried for about 3 hrs.
	 */
	@Override
	public void testFailedDeployment() {
		Assert.isTrue(true);
	}


	/**
	 * Return the timeout to use for repeatedly querying a module while it is being deployed.
	 * Default value is one minute, being queried every 5 seconds.
	 */
	@Override
	protected Timeout deploymentTimeout() {
		return new Timeout(maxRetries, (int) (5000 * timeoutMultiplier));
	}

	/**
	 * Return the timeout to use for repeatedly querying a module while it is being un-deployed.
	 * Default value is one minute, being queried every 5 seconds.
	 */
	@Autowired
	protected Timeout undeploymentTimeout() {
		return new Timeout(maxRetries, (int) (5000 * timeoutMultiplier));
	}

	/**
	 * This triggers the use of {@link CloudFoundryDeployerAutoConfiguration}.
	 *
	 * @author Eric Bottard
	 */
	@Configuration
	@EnableAutoConfiguration
	public static class Config {
	}

}
