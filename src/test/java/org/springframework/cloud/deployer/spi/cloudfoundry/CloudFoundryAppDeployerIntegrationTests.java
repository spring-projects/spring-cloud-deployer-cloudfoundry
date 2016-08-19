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
import java.util.Properties;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.IntegrationTest;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.cloud.deployer.resource.maven.MavenProperties;
import org.springframework.cloud.deployer.resource.maven.MavenResource;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.test.AbstractAppDeployerIntegrationTests;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Integration tests for CloudFoundryAppDeployer.
 *
 * @author Eric Bottard
 * @author Greg Turnquist
 */
@SpringApplicationConfiguration(classes = CloudFoundryAppDeployerIntegrationTests.Config.class)
@IntegrationTest("spring.cloud.deployer.cloudfoundry.enableRandomAppNamePrefix=false")
public class CloudFoundryAppDeployerIntegrationTests extends AbstractAppDeployerIntegrationTests {

	@ClassRule
	public static CloudFoundryTestSupport cfAvailable = new CloudFoundryTestSupport();

	@Autowired
	private AppDeployer appDeployer;

	@Override
	protected AppDeployer appDeployer() {
		return appDeployer;
	}

	@Autowired
	private MavenProperties mavenProperties;

	/**
	 * Execution environments may override this default value to have tests wait longer for a deployment, for example if
	 * running in an environment that is known to be slow.
	 */
	protected double timeoutMultiplier = 1.0D;

	protected int maxRetries = 60;

	@Before
	public void init() {
		String multiplier = System.getenv("CF_DEPLOYER_TIMEOUT_MULTIPLIER");
		if (multiplier != null) {
			timeoutMultiplier = Double.parseDouble(multiplier);
		}
	}

	@Override
	@Ignore("Need to look into args escaping better. Disabling for the time being")
	public void testCommandLineArgumentsPassing() {
	}

	@Override
	protected String randomName() {
		// This will become the hostname part and is limited to 63 chars
		String name = super.randomName();
		return name.substring(0, Math.min(63, name.length()));
	}

	@Override
	protected Timeout deploymentTimeout() {
		return new Timeout(maxRetries, (int) (5000 * timeoutMultiplier));
	}

	@Override
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
	@EnableConfigurationProperties
	public static class Config {

		@Bean
		@ConfigurationProperties("maven")
		public MavenProperties mavenProperties() {
			return new MavenProperties();
		}


	}

	// Overridden here for imminent release until
	// https://github.com/spring-cloud/spring-cloud-deployer/issues/102 is fixed
	@Override
	protected Resource integrationTestProcessor() {
		Properties properties = new Properties();
		try {
			properties.load(new ClassPathResource("integration-test-app.properties").getInputStream());
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to determine which version of integration-test-app to use", e);
		}
		return new MavenResource.Builder(mavenProperties)
				.groupId("org.springframework.cloud")
				.artifactId("spring-cloud-deployer-spi-test-app")
				.classifier("exec")
				.version(properties.getProperty("version"))
				.extension("jar")
				.build();
	}
}
