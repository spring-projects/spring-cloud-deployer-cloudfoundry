/*
 * Copyright 2018 the original author or authors.
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

package org.springframework.cloud.deployer.scheduler.spi.cloudfoundry;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.pivotal.reactor.scheduler.ReactorSchedulerClient;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.runner.RunWith;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.deployer.CloudFoundryTestSupport;
import org.springframework.cloud.deployer.resource.maven.MavenProperties;
import org.springframework.cloud.deployer.scheduler.spi.core.Scheduler;
import org.springframework.cloud.deployer.scheduler.spi.core.SchedulerPropertyKeys;
import org.springframework.cloud.deployer.scheduler.spi.test.AbstractSchedulerIntegrationTests;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryConnectionProperties;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryTaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

/**
 * Integration tests for CloudFoundryAppScheduler.
 *
 * @author Glenn Renfro
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = NONE)
@ContextConfiguration(classes = {SpringCloudSchedulerIntegrationTests.Config.class})
public class SpringCloudSchedulerIntegrationTests extends AbstractSchedulerIntegrationTests {
	@ClassRule
	public static CloudFoundryTestSupport cfAvailable = new CloudFoundryTestSupport();

	@Autowired
	protected MavenProperties mavenProperties;

	@Autowired
	private Scheduler scheduler;

	@Value("${spring.cloud.deployer.cloudfoundry.services}")
	private String deployerProps;

	@Override
	protected Scheduler provideScheduler() {
		return this.scheduler;
	}

	@Autowired
	private CloudFoundryOperations operations;

	@Override
	protected List<String> getCommandLineArgs() {
		return null;
	}

	@Override
	protected Map<String, String> getSchedulerProperties() {
		return Collections.singletonMap(SchedulerPropertyKeys.CRON_EXPRESSION,"41 17 ? * *");
	}

	@Override
	protected Map<String, String> getDeploymentProperties() {
		return Collections.singletonMap(CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY, deployerProps);
	}

	@Override
	protected Map<String, String> getAppProperties() {
		return null;
	}

	/**
	 * Remove all pushed apps.  This in turn removes the associated schedules.
	 */
	@After
	public void tearDown() {
		try {
			operations.applications().list().flatMap(applicationSummary -> {
				if (applicationSummary.getName().startsWith("testList") ||
						applicationSummary.getName().startsWith("testDuplicateSchedule") ||
						applicationSummary.getName().startsWith("testUnschedule") ||
						applicationSummary.getName().startsWith("testMultiple") ||
						applicationSummary.getName().startsWith("testSimpleSchedule")) {

					return operations.applications().delete(DeleteApplicationRequest
							.builder()
							.name(applicationSummary.getName())
							.build());
				}
				return Mono.justOrEmpty(applicationSummary);
			}).blockLast();
		} catch (Exception ex) {
			log.warn("Attempted cleanup and exception occured: " + ex.getMessage());
		}
	}

	@Configuration
	@EnableAutoConfiguration
	@EnableConfigurationProperties
	public static class Config {
		@Bean
		@ConditionalOnMissingBean
		public ReactorSchedulerClient reactorSchedulerClient(ConnectionContext context,
				TokenProvider passwordGrantTokenProvider,
				CloudFoundrySchedulerProperties properties) {
			return ReactorSchedulerClient.builder()
					.connectionContext(context)
					.tokenProvider(passwordGrantTokenProvider)
					.root(Mono.just(properties.getSchedulerUrl()))
					.build();
		}

		@Bean
		@ConditionalOnMissingBean
		public Scheduler scheduler(ReactorSchedulerClient client,
				CloudFoundryOperations operations,
				CloudFoundryConnectionProperties properties,
				TaskLauncher taskLauncher,
				CloudFoundrySchedulerProperties schedulerProperties) {
			return new CloudFoundryAppScheduler(client, operations, properties,
					(CloudFoundryTaskLauncher) taskLauncher,
					schedulerProperties);
		}

		@Bean
		@ConditionalOnMissingBean
		public CloudFoundrySchedulerProperties cloudFoundrySchedulerProperties() {
			return new CloudFoundrySchedulerProperties();
		}
	}
}
