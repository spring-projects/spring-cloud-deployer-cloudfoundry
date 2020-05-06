/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.cloudfoundry;

import java.util.Collections;
import java.util.Map;
import org.cloudfoundry.util.FluentMap;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CfEnvConfigurerTests {

	@Test
	public void testCfEnvConfigurerActivateCloudProfile() {
		Map<String, String> updatedEnv = CfEnvConfigurer.activateCloudProfile(
				Collections.EMPTY_MAP, CfEnvConfigurer.SPRING_PROFILES_ACTIVE_FQN);
		assertThat(updatedEnv).containsEntry(CfEnvConfigurer.SPRING_PROFILES_ACTIVE_FQN,
				CfEnvConfigurer.CLOUD_PROFILE_NAME);
		assertThat(updatedEnv).doesNotContainKeys(
				CfEnvConfigurer.SPRING_PROFILES_ACTIVE,
				CfEnvConfigurer.SPRING_PROFILES_ACTIVE_HYPHENATED);
	}

	@Test
	public void testCfEnvConfigurerDoNotActivateCloudProfileIfNoneExists() {
		Map<String, String> updatedEnv = CfEnvConfigurer.activateCloudProfile(
				Collections.EMPTY_MAP, null);
		assertThat(updatedEnv).doesNotContainKeys(CfEnvConfigurer.SPRING_PROFILES_ACTIVE_FQN,
				CfEnvConfigurer.SPRING_PROFILES_ACTIVE,
				CfEnvConfigurer.SPRING_PROFILES_ACTIVE_HYPHENATED);
	}

	@Test
	public void testCfEnvConfigurerActivateCloudProfileWithEmptyValue() {
		Map<String, String> updatedEnv = CfEnvConfigurer.activateCloudProfile(
				FluentMap.<String, String>builder()
						.entry(CfEnvConfigurer.SPRING_PROFILES_ACTIVE, " ")
						.build(),
				CfEnvConfigurer.SPRING_PROFILES_ACTIVE);
		assertThat(updatedEnv).containsEntry(CfEnvConfigurer.SPRING_PROFILES_ACTIVE,
				CfEnvConfigurer.CLOUD_PROFILE_NAME);
		assertThat(updatedEnv).doesNotContainKeys(CfEnvConfigurer.SPRING_PROFILES_ACTIVE_FQN,
				CfEnvConfigurer.SPRING_PROFILES_ACTIVE_HYPHENATED);
	}

	@Test
	public void testCfEnvConfigurerAppendToActiveProfiles() {
		Map<String, String> updatedEnv = CfEnvConfigurer.activateCloudProfile(
				FluentMap.<String, String>builder()
						.entry(CfEnvConfigurer.SPRING_PROFILES_ACTIVE_FQN, "foo,bar")
						.build(),
				CfEnvConfigurer.SPRING_PROFILES_ACTIVE);
		assertThat(updatedEnv).containsEntry(CfEnvConfigurer.SPRING_PROFILES_ACTIVE_FQN, "foo,bar,cloud");
		assertThat(updatedEnv).doesNotContainKeys(CfEnvConfigurer.SPRING_PROFILES_ACTIVE,
				CfEnvConfigurer.SPRING_PROFILES_ACTIVE_HYPHENATED);
	}

	@Test
	public void testCloudProfileAppendedToCommandLineArgs() {
		assertThat(CfEnvConfigurer.appendCloudProfileToSpringProfilesActiveArg("--spring-profiles-active=foo,bar"))
				.isEqualTo("--spring-profiles-active=foo,bar,cloud");
		assertThat(CfEnvConfigurer.appendCloudProfileToSpringProfilesActiveArg("--spring.profiles.active=foo"))
				.isEqualTo("--spring.profiles.active=foo,cloud");
		assertThat(CfEnvConfigurer.appendCloudProfileToSpringProfilesActiveArg("--baz=foo"))
				.isEqualTo("--baz=foo");
	}

}
