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

/**
 * API for further customizing the behavior of the deployed streams using {@link org.springframework.cloud.deployer.spi.app.AppDeployer}
 * such as changing the deploymentId on the targeted platform.
 *
 *
 * @author Soby Chacko
 */
public interface AppDeploymentCustomizer {

	/**
	 * Contract for providing a unique deployment id per user per dataflow server. By using this API, different users
	 * can provide similarly named streams (such as <i>ticktock</i>) and the deployer would generate a unique prefix
	 * for each of those users and attach to the streams to handle multiple deployment of apps on those streams.
	 * This deployment ID with the unique prefix will be part of the name and route created by the
	 * targeted platform under the same domain.
	 *
	 * A concrete example would be when multiple users create the same stream name under the same domain on CloudFoundry.
	 *
	 * @param appName application name (stream-app)
	 * @return deployment ID with a unique prefix
	 */
	String deploymentIdWithUniquePrefix(String appName);

}
