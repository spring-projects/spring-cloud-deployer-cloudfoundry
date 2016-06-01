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

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v3.applications.ApplicationResource;
import org.cloudfoundry.client.v3.applications.ApplicationsV3;
import org.cloudfoundry.client.v3.applications.ListApplicationDropletsResponse;
import org.cloudfoundry.client.v3.applications.ListApplicationsResponse;
import org.cloudfoundry.client.v3.droplets.DropletResource;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.cloudfoundry.client.v3.tasks.State;
import org.cloudfoundry.client.v3.tasks.Tasks;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;

/**
 * @author Michael Minella
 */
public class CloudFoundryTaskLauncherTests {

    @Mock
    private CloudFoundryClient client;

    @Mock
    private CloudFoundryOperations operations;

    @Mock
    private Tasks tasks;

    @Mock
    private ApplicationsV3 applicationsV3;

    private CloudFoundryTaskLauncher launcher;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        this.launcher = new CloudFoundryTaskLauncher(this.client, this.operations, new CloudFoundryDeployerProperties());
    }

    @Test
    public void testStatus() {

        // given
        given(client.tasks()).willReturn(tasks);
        given(tasks.get(any())).willReturn(Mono.just(GetTaskResponse.builder()
            .id("foo")
            .state(State.SUCCEEDED_STATE)
            .build()));

        // when
        TaskStatus status = this.launcher.status("foo");

        // then
        assertThat(status.getState(), equalTo(LaunchState.complete));
    }

    @Test(expected = IllegalStateException.class)
    public void testStatusTimeout() {

        // given
        given(client.tasks()).willReturn(tasks);
        given(tasks.get(any())).willReturn(Mono.delay(Duration.ofSeconds(35)).then(Mono.just(GetTaskResponse.builder()
                    .state(State.SUCCEEDED_STATE)
                    .build())));

        // when
        TaskStatus status = this.launcher.status("bar");
    }

    @Test
    public void testLaunchTask() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();

        ListApplicationsResponse listApplicationsResponse = ListApplicationsResponse.builder()
            .resource(ApplicationResource.builder()
                .id(applicationId.toString())
                .build())
            .build();
        ListApplicationDropletsResponse listApplicationDropletsResponse = ListApplicationDropletsResponse.builder()
            .resource(DropletResource.builder()
                .id(dropletId.toString())
                .build())
            .build();
        given(this.client.applicationsV3()).willReturn(this.applicationsV3);
        given(this.applicationsV3.list(any())).willReturn(Mono.just(listApplicationsResponse));
        given(this.applicationsV3.listDroplets(any())).willReturn(Mono.just(listApplicationDropletsResponse));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        AppDeploymentRequest request = new AppDeploymentRequest(definition, new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"));
        String launch = this.launcher.launch(request);

        // then
    }

    @Test
    @Ignore
    public void testLaunchTaskAppExists() {}

    @Test
    @Ignore
    public void testLaunchTaskCantGetAppStatus() {}

    @Test
    @Ignore
    public void testLaunchTaskAppCreationFails() {}

    @Test
    @Ignore
    public void testLaunchTaskAppStatusFails() {}

    @Test
    @Ignore
    public void testLaunchTaskCreatePackageFails() {}

    @Test
    @Ignore
    public void testLaunchTaskUploadPackageFails() {}

    @Test
    @Ignore
    public void testLaunchTaskPackageStatusFails() {}

    @Test
    @Ignore
    public void testLaunchTaskCreateDropletFails() {}

    @Test
    @Ignore
    public void testLaunchTaskDropletStatusFails() {}

    @Test
    @Ignore
    public void testLaunchTaskNoServicesToBind() {}

    @Test
    @Ignore
    public void testLaunchTaskOneServiceToBind() {}

    @Test
    @Ignore
    public void testLaunchTaskThreeServicesToBind() {}

    @Test
    @Ignore
    public void testLaunchTaskServiceBindFails() {}

    @Test
    @Ignore
    public void testLaunchTaskGetDropletFails() {}

    @Test
    @Ignore
    public void testLaunchTaskCreateTaskFails() {}
}
