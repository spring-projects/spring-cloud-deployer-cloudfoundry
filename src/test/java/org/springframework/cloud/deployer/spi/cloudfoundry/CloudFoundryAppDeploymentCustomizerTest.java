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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import org.junit.Test;

import org.springframework.test.util.ReflectionTestUtils;

/**
 * @author Soby Chacko
 */
public class CloudFoundryAppDeploymentCustomizerTest {

	@Test
	public void testDeploymentIdWithUniquePrefix() throws Exception {
		CloudFoundryDeployerProperties properties = new CloudFoundryDeployerProperties();
		properties.setAppPrefix("dataflow-foobar");
		AppDeploymentCustomizer deploymentCustomizer = new CloudFoundryAppDeploymentCustomizer(properties, new WordListRandomWords());
		((CloudFoundryAppDeploymentCustomizer)deploymentCustomizer).afterPropertiesSet();

		assertEquals(deploymentCustomizer.deploymentIdWithUniquePrefix("foo"), "dataflow-foobar-foo");
	}

	@Test
	public void testDeploymentIdWithUniquePrefixWhenNoDataflowNameProvided() throws Exception {
		AppDeploymentCustomizer deploymentCustomizer = new CloudFoundryAppDeploymentCustomizer(new CloudFoundryDeployerProperties(), new WordListRandomWords());
		((CloudFoundryAppDeploymentCustomizer)deploymentCustomizer).afterPropertiesSet();

		String deploymentIdWithUniquePrefix = deploymentCustomizer.deploymentIdWithUniquePrefix("foo");
		assertTrue(deploymentIdWithUniquePrefix.startsWith("dataflow-"));
		assertTrue(deploymentIdWithUniquePrefix.endsWith("-foo"));
		assertTrue(deploymentIdWithUniquePrefix.matches("dataflow-\\w+-\\w+-foo"));

		String deploymentIdWithUniquePrefixAgain = deploymentCustomizer.deploymentIdWithUniquePrefix("foo");

		assertEquals(deploymentIdWithUniquePrefix, deploymentIdWithUniquePrefixAgain);
	}

	@Test
	public void testDeploymentIdWithUniquePrefixWhenCustomSpringApplicationNameIsProvided() throws Exception {
		AppDeploymentCustomizer deploymentCustomizer = new CloudFoundryAppDeploymentCustomizer(new CloudFoundryDeployerProperties(), new WordListRandomWords());
		ReflectionTestUtils.setField(deploymentCustomizer, "springApplicationName", "custom-dataflow");
		((CloudFoundryAppDeploymentCustomizer)deploymentCustomizer).afterPropertiesSet();

		String deploymentIdWithUniquePrefix = deploymentCustomizer.deploymentIdWithUniquePrefix("foobar");
		assertTrue(deploymentIdWithUniquePrefix.startsWith("custom-dataflow-"));
		assertTrue(deploymentIdWithUniquePrefix.endsWith("-foobar"));
		assertTrue(deploymentIdWithUniquePrefix.matches("custom-dataflow-\\w+-\\w+-foobar"));

		String deploymentIdWithUniquePrefixAgain = deploymentCustomizer.deploymentIdWithUniquePrefix("foobar");

		assertEquals(deploymentIdWithUniquePrefix, deploymentIdWithUniquePrefixAgain);
	}

	@Test
	public void testDeploymentIdWithUniquePrefixWhenSpringApplicationNameDefaultsToJarName() throws Exception {
		AppDeploymentCustomizer deploymentCustomizer = new CloudFoundryAppDeploymentCustomizer(new CloudFoundryDeployerProperties(), new WordListRandomWords());
		ReflectionTestUtils.setField(deploymentCustomizer, "springApplicationName", "spring-cloud-dataflow-server-cloudfoundry");
		((CloudFoundryAppDeploymentCustomizer)deploymentCustomizer).afterPropertiesSet();

		String deploymentIdWithUniquePrefix = deploymentCustomizer.deploymentIdWithUniquePrefix("foobar");
		assertTrue(deploymentIdWithUniquePrefix.startsWith("dataflow-"));
		assertTrue(deploymentIdWithUniquePrefix.endsWith("-foobar"));
		assertTrue(deploymentIdWithUniquePrefix.matches("dataflow-\\w+-\\w+-foobar"));

		String deploymentIdWithUniquePrefixAgain = deploymentCustomizer.deploymentIdWithUniquePrefix("foobar");

		assertEquals(deploymentIdWithUniquePrefix, deploymentIdWithUniquePrefixAgain);
	}

}
