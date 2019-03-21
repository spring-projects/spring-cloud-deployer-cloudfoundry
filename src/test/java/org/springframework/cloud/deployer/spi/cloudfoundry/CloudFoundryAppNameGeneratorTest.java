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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * @author Soby Chacko
 * @author Mark Pollack
 */
public class CloudFoundryAppNameGeneratorTest {

	@Test
	public void testDeploymentIdWithAppNamePrefixAndRandomAppNamePrefixFalse() throws Exception {
		CloudFoundryDeploymentProperties properties = new CloudFoundryDeploymentProperties();
		properties.setEnableRandomAppNamePrefix(false);
		properties.setAppNamePrefix("dataflow");
		CloudFoundryAppNameGenerator deploymentCustomizer =
				new CloudFoundryAppNameGenerator(properties, new WordListRandomWords());
		deploymentCustomizer.afterPropertiesSet();

		assertEquals("dataflow-foo", deploymentCustomizer.generateAppName("foo") );
	}

	@Test
	public void testDeploymentIdWithAppNamePrefixAndRandomAppNamePrefixTrue() throws Exception {
		CloudFoundryDeploymentProperties properties = new CloudFoundryDeploymentProperties();
		properties.setEnableRandomAppNamePrefix(true);
		properties.setAppNamePrefix("dataflow-longername");
		CloudFoundryAppNameGenerator deploymentCustomizer =
				new CloudFoundryAppNameGenerator(properties, new WordListRandomWords());
		deploymentCustomizer.afterPropertiesSet();

		String deploymentIdWithUniquePrefix = deploymentCustomizer.generateAppName("foo");
		assertTrue(deploymentIdWithUniquePrefix.startsWith("dataflow-"));
		assertTrue(deploymentIdWithUniquePrefix.endsWith("-foo"));
		assertTrue(deploymentIdWithUniquePrefix.matches("dataflow-longername-\\w+-\\w+-foo"));

		String deploymentIdWithUniquePrefixAgain = deploymentCustomizer.generateAppName("foo");

		assertEquals(deploymentIdWithUniquePrefix, deploymentIdWithUniquePrefixAgain);
	}

	@Test
	public void testDeploymentIdWithoutAppNamePrefixAndRandomAppNamePrefixTrue() throws Exception {
		CloudFoundryDeploymentProperties properties = new CloudFoundryDeploymentProperties();
		properties.setEnableRandomAppNamePrefix(true);
		properties.setAppNamePrefix("");
		CloudFoundryAppNameGenerator deploymentCustomizer =
				new CloudFoundryAppNameGenerator(properties, new WordListRandomWords());
		deploymentCustomizer.afterPropertiesSet();

		String deploymentIdWithUniquePrefix = deploymentCustomizer.generateAppName("foo");
		assertTrue(deploymentIdWithUniquePrefix.endsWith("-foo"));

		assertTrue(deploymentIdWithUniquePrefix.matches("\\w+-\\w+-foo"));
	}

	@Test
	public void testDeploymentIdWithoutAppNamePrefixAndRandomAppNamePrefixFalse() throws Exception {
		CloudFoundryDeploymentProperties properties = new CloudFoundryDeploymentProperties();
		properties.setEnableRandomAppNamePrefix(false);
		properties.setAppNamePrefix("");
		CloudFoundryAppNameGenerator deploymentCustomizer =
				new CloudFoundryAppNameGenerator(properties, new WordListRandomWords());
		deploymentCustomizer.afterPropertiesSet();

		assertEquals("foo", deploymentCustomizer.generateAppName("foo"));
	}

}
