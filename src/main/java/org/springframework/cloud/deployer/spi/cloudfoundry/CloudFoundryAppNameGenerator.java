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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.StringUtils;

/**
 * CloudFoundry specific implementation of {@link AppNameGenerator}.  This takes into account the
 * configuration properties {@code enableRandomAppNamePrefix} and {@code appNamePrefix}.
 *
 * If {@code enableRandomAppNamePrefix} a random adjective and noun are prefixed to the base name.
 * It is true by default.  If {@code appNamePrefix} is set, it is also prefixed before the random adjective and noun.
 * By default the {@code appNamePrefix} is the value of {@code spring.application.name} if available,
 * otherwise the empty string.
 *
 * As an example, if {@code enableRandomAppNamePrefix=true}, {@code spring.application.name=server},
 * and we are deploying an application whose base name is {@code time}, then the deployed name on Cloud Foundry
 * will be {@code server-unfarming-restabilization-time}
 *
 *
 * @author Soby Chacko
 * @author Mark Pollack
 */
public class CloudFoundryAppNameGenerator implements AppNameGenerator, InitializingBean {

	private static final Log logger = LogFactory.getLog(CloudFoundryAppNameGenerator.class);

	private String prefixToUse = "";

	private final CloudFoundryDeploymentProperties properties;

	private final WordListRandomWords wordListRandomWords;

	public CloudFoundryAppNameGenerator(CloudFoundryDeploymentProperties cloudFoundryDeploymentProperties,
										WordListRandomWords wordListRandomWords) {
		this.properties = cloudFoundryDeploymentProperties;
		this.wordListRandomWords = wordListRandomWords;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		if (properties.isEnableRandomAppNamePrefix()) {
			prefixToUse = createUniquePrefix();
			if (!StringUtils.isEmpty(properties.getAppNamePrefix())) {
				prefixToUse = String.format("%s-%s", properties.getAppNamePrefix(), prefixToUse);
			}
		} else {
			if (!StringUtils.isEmpty(properties.getAppNamePrefix())) {
				prefixToUse = properties.getAppNamePrefix();
			}
		}
		logger.info(String.format("Prefix to be used for deploying apps: %s", prefixToUse));
	}


	@Override
	public String generateAppName(String appName) {
		if (StringUtils.isEmpty(prefixToUse)) {
			return appName;
		} else {
			return String.format("%s-%s", prefixToUse, appName);
		}
	}

	private String createUniquePrefix() {
		return String.format("%s-%s", wordListRandomWords.getAdjective(), wordListRandomWords.getNoun());
	}


}
