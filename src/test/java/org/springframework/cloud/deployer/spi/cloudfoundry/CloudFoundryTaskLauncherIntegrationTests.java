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

import org.junit.After;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.test.AbstractTaskLauncherIntegrationTests;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;

/**
 * Runs integration tests for {@link CloudFoundry2630AndLaterTaskLauncher}, using the production configuration,
 * that may be configured via {@link CloudFoundryConnectionProperties}.
 *
 * Tests are only run if a successful connection can be made at startup.
 *
 * @author Eric Bottard
 * @author Greg Turnquist
 * @author Michael Minella
 * @author Ben Hale
 */
@ContextConfiguration(classes=CloudFoundryTaskLauncherIntegrationTests.Config.class)
public class CloudFoundryTaskLauncherIntegrationTests extends AbstractTaskLauncherIntegrationTests {

	@ClassRule
	public static CloudFoundryTestSupport cfAvailable = new CloudFoundryTestSupport();

	@Autowired
	private TaskLauncher taskLauncher;

	@Override
	protected TaskLauncher taskLauncher() {
		return taskLauncher;
	}


	/*
	 * Allow for a small pause so that each each TL.destroy() at the end of tests actually completes,
	 * as this is asynchronous.
	 */
	@After
	public void pause() throws InterruptedException {
		Thread.sleep(500);
	}

	@Test
	@Override
	@Ignore("CF Deployer incorrectly reports status as failed instead of canceled")
	public void testSimpleCancel() throws InterruptedException {
		super.testSimpleCancel();
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

	}

}
