package org.springframework.cloud.deployer.spi.cloudfoundry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.deployer.resource.maven.MavenProperties;
import org.springframework.context.annotation.Bean;

/**
 * Created by ericbottard on 02/11/16.
 */
public class MavenConfiguration {
	// Added as a workaround to superclass config not being picked up
	@Bean
	@ConfigurationProperties("maven")
	public MavenProperties mavenProperties() {
		return new MavenProperties();
	}
}
