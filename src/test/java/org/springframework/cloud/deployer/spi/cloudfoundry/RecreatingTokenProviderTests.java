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

import java.util.function.Supplier;

import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class RecreatingTokenProviderTests {

	@Test
	public void testZero() {
		ConnectionContext connectionContext = mock(ConnectionContext.class);
		TestSupplier supplier = new TestSupplier();
		RecreatingTokenProvider rProvider = new RecreatingTokenProvider(0, supplier);
		rProvider.getToken(connectionContext);
		assertThat(supplier.supplied).isEqualTo(1);
		rProvider.getToken(connectionContext);
		assertThat(supplier.supplied).isEqualTo(1);
	}

	@Test
	public void testNull() {
		ConnectionContext connectionContext = mock(ConnectionContext.class);
		TestSupplier supplier = new TestSupplier();
		RecreatingTokenProvider rProvider = new RecreatingTokenProvider(null, supplier);
		rProvider.getToken(connectionContext);
		assertThat(supplier.supplied).isEqualTo(1);
		rProvider.getToken(connectionContext);
		assertThat(supplier.supplied).isEqualTo(1);
	}

	@Test
	public void testNegative() throws Exception {
		ConnectionContext connectionContext = mock(ConnectionContext.class);
		TestSupplier supplier = new TestSupplier();
		RecreatingTokenProvider rProvider = new RecreatingTokenProvider(-1, supplier);
		rProvider.getToken(connectionContext);
		assertThat(supplier.supplied).isEqualTo(1);
		Thread.sleep(1100);
		rProvider.getToken(connectionContext);
		assertThat(supplier.supplied).isEqualTo(1);
	}

	@Test
	public void testOneSecond() throws Exception {
		ConnectionContext connectionContext = mock(ConnectionContext.class);
		TestSupplier supplier = new TestSupplier();
		RecreatingTokenProvider rProvider = new RecreatingTokenProvider(1, supplier);
		rProvider.getToken(connectionContext);
		assertThat(supplier.supplied).isEqualTo(1);
		Thread.sleep(1100);
		rProvider.getToken(connectionContext);
		assertThat(supplier.supplied).isEqualTo(2);
	}

	private static class TestSupplier implements Supplier<TokenProvider> {
		int supplied = 0;

		@Override
		public TokenProvider get() {
			supplied++;
			return new TokenProvider(){

				@Override
				public Mono<String> getToken(ConnectionContext connectionContext) {
					return Mono.just("");
				}
			};
		}
	}
}
