/*
 * Copyright 2017 the original author or authors.
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

import com.github.zafarkhaja.semver.Version;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * JUnit {@link org.junit.Rule} that detects whether the CF environment is compatible with the task launcher.
 *
 * @author Eric Bottard
 * @author Ilayaperumal Gopinathan
 */
public class CloudFoundryTaskLauncherTestSupport extends CloudFoundryTestSupport {

	@Override
	protected void obtainResource() throws Exception {
		super.obtainResource();
		AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext();
		applicationContext.register(CloudFoundryDeployerAutoConfiguration.EarlyConnectionConfiguration.class);
		applicationContext.refresh();
		try {
			Version version = applicationContext.getBean(Version.class);
			if (version != null && version.lessThan(UnsupportedVersionTaskLauncher.MINIMUM_SUPPORTED_VERSION)) {
				throw new RuntimeException("Skipping test due to incompatible CloudFoundry API " + version);
			}
		} catch (Exception e) {
			throw new RuntimeException("Skipping test because CloudFoundry API version could not be found" + e);
		} finally {
			applicationContext.close();
		}
	}

}
