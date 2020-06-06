/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.cloud.deployer.spi.cloudfoundry;

import java.time.Duration;
import java.util.function.Supplier;

import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.util.Assert;

/**
 * {@link TokenProvider} which tracks when delegating provider is created
 * allowing to re-create it based on token provider validity duration. This
 * implementation mostly exists to overcome CFJC issue where it doesn't
 * work anymore when refresh token gets invalid, thus we re-create to force
 * CFJC to re-login.
 *
 * @author Janne Valkealahti
 *
 */
public class RecreatingTokenProvider implements TokenProvider {

	private final static Logger log = LoggerFactory.getLogger(RecreatingTokenProvider.class);
	private final Supplier<TokenProvider> supplier;
	private TokenProvider tokenProvider;
	private long tokenProviderValidity;
	private long createdTime;

	/**
	 * Intantiate a new recreating token provider.
	 *
	 * If given token provider validity is higher than 0, {@link TokenProvider} is
	 * re-created using a given supplier.
	 *
	 * @param tokenProviderValidity the validity time in millis
	 * @param supplier the token provider supplier
	 */
	public RecreatingTokenProvider(Integer tokenProviderValidity, Supplier<TokenProvider> supplier) {
		Assert.notNull(supplier, "TokenProvider supplier must be set");
		this.tokenProviderValidity = tokenProviderValidity != null ? Duration.ofSeconds(tokenProviderValidity).toMillis() : 0;
		this.supplier = supplier;
		this.tokenProvider = createTokenProvider();
	}

	@Override
	public Mono<String> getToken(ConnectionContext connectionContext) {
		log.trace("getToken {} {}", connectionContext, this);
		return getOrRecreateTokenProvider().getToken(connectionContext);
	}

	@Override
	public void invalidate(ConnectionContext connectionContext) {
		log.trace("invalidate {} {}", connectionContext, this);
		this.tokenProvider.invalidate(connectionContext);
	}

	/**
	 * Gets a token provider validity.
	 *
	 * @return token provider validity
	 */
	public long getTokenProviderValidity() {
		return tokenProviderValidity;
	}

	/**
	 * Sets a token provider validity.
	 *
	 * @param tokenProviderValidity the validity time in millis
	 */
	public void setTokenProviderValidity(long tokenProviderValidity) {
		this.tokenProviderValidity = tokenProviderValidity;
	}

	private TokenProvider createTokenProvider() {
		createdTime = System.currentTimeMillis();
		return this.supplier.get();
	}

	private synchronized TokenProvider getOrRecreateTokenProvider() {
		if (tokenProviderValidity > 0 && System.currentTimeMillis() > (createdTime + tokenProviderValidity)) {
			this.tokenProvider = createTokenProvider();
			log.debug("recreated token provider {}", this.tokenProvider);
		}
		return this.tokenProvider;
	}
}
