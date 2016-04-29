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

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.Applications;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.FileSystemResource;

/**
 * @author Greg Turnquist
 */
public class CloudFoundryAppDeployerTests {

    CloudFoundryOperations operations;

    Applications applications;

    CloudFoundryAppDeployer deployer;

    @Before
    public void setUp() {

        operations = mock(CloudFoundryOperations.class);
        applications = mock(Applications.class);
        deployer = new CloudFoundryAppDeployer(new CloudFoundryDeployerProperties(), operations);
    }

    @Test
    public void shouldSwitchToSimpleDeploymentIdWhenGroupIsLeftOut() {

        when(operations.applications()).thenReturn(applications);
        when(applications.get(any())).thenReturn(Mono.just(ApplicationDetail.builder().build()));

        String deploymentId = deployer.deploy(new AppDeploymentRequest(
            new AppDefinition("test", Collections.emptyMap()),
            new FileSystemResource("")));

        assertThat(deploymentId, equalTo("test"));
    }

    @Test
    public void shouldNamespaceTheDeploymentIdWhenAGroupIsUsed() {

        when(operations.applications()).thenReturn(applications);
        when(applications.get(any())).thenReturn(Mono.just(ApplicationDetail.builder().build()));

        String deploymentId = deployer.deploy(new AppDeploymentRequest(
            new AppDefinition("test", Collections.emptyMap()),
            new FileSystemResource(""),
            Collections.singletonMap(AppDeployer.GROUP_PROPERTY_KEY, "prefix")));

        assertThat(deploymentId, equalTo("prefix-test"));
    }

}
