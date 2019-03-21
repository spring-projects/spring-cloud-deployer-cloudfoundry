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

import java.net.URL;

import javax.validation.constraints.NotNull;

/**
 * Holds configuration properties for connecting to a Cloud Foundry runtime.
 *
 * @author Eric Bottard
 * @author Greg Turnquist
 */

public class CloudFoundryConnectionProperties {

	/**
	 * Top level prefix for Cloud Foundry related configuration properties.
	 */
	public static final String CLOUDFOUNDRY_PROPERTIES = "spring.cloud.deployer.cloudfoundry";

	/**
	 * The organization to use when registering new applications.
	 */
	@NotNull
	private String org;

	/**
	 * The space to use when registering new applications.
	 */
	@NotNull
	private String space;

	/**
	 * Location of the CloudFoundry REST API endpoint to use.
	 */
	@NotNull
	private URL url;

	/**
	 * Username to use to authenticate against the Cloud Foundry API.
	 */
	@NotNull
	private String username;

	/**
	 * Password to use to authenticate against the Cloud Foundry API.
	 */
	@NotNull
	private String password;

	/**
	 * Allow operation using self-signed certificates.
	 */
	private boolean skipSslValidation = false;

	public String getOrg() {
		return org;
	}

	public void setOrg(String org) {
		this.org = org;
	}

	public String getSpace() {
		return space;
	}

	public void setSpace(String space) {
		this.space = space;
	}

	public URL getUrl() {
		return url;
	}

	public void setUrl(URL url) {
		this.url = url;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public boolean isSkipSslValidation() {
		return skipSslValidation;
	}

	public void setSkipSslValidation(boolean skipSslValidation) {
		this.skipSslValidation = skipSslValidation;
	}

}
