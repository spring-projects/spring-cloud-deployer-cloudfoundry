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
import org.cloudfoundry.client.v2.Metadata;
import org.cloudfoundry.client.v2.spaces.ListSpacesResponse;
import org.cloudfoundry.client.v2.spaces.SpaceEntity;
import org.cloudfoundry.client.v2.spaces.SpaceResource;
import org.cloudfoundry.client.v2.spaces.Spaces;
import org.cloudfoundry.client.v3.applications.ApplicationResource;
import org.cloudfoundry.client.v3.applications.ApplicationsV3;
import org.cloudfoundry.client.v3.applications.CreateApplicationResponse;
import org.cloudfoundry.client.v3.applications.ListApplicationDropletsResponse;
import org.cloudfoundry.client.v3.applications.ListApplicationsResponse;
import org.cloudfoundry.client.v3.droplets.DropletResource;
import org.cloudfoundry.client.v3.droplets.Droplets;
import org.cloudfoundry.client.v3.droplets.GetDropletResponse;
import org.cloudfoundry.client.v3.droplets.StagedResult;
import org.cloudfoundry.client.v3.packages.CreatePackageResponse;
import org.cloudfoundry.client.v3.packages.GetPackageRequest;
import org.cloudfoundry.client.v3.packages.GetPackageResponse;
import org.cloudfoundry.client.v3.packages.Packages;
import org.cloudfoundry.client.v3.packages.StagePackageResponse;
import org.cloudfoundry.client.v3.packages.UploadPackageResponse;
import org.cloudfoundry.client.v3.tasks.CreateTaskResponse;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.cloudfoundry.client.v3.tasks.State;
import org.cloudfoundry.client.v3.tasks.Tasks;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.services.Services;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
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
    private Droplets droplets;

    @Mock
    private Spaces spaces;

    @Mock
    private Packages packages;

    @Mock
    private Services services;

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
    public void testLaunchTaskAppExists() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();
        UUID taskleltId = UUID.randomUUID();

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
        GetDropletResponse getDropletResponse = GetDropletResponse.builder()
            .result(StagedResult.builder()
                .processType("web", "java -jar foo.jar")
                .build())
            .build();
        CreateTaskResponse createTaskResponse = CreateTaskResponse.builder()
            .id(taskleltId.toString())
            .build();

        given(this.client.applicationsV3()).willReturn(this.applicationsV3);
        given(this.applicationsV3.list(any())).willReturn(Mono.just(listApplicationsResponse));
        given(this.applicationsV3.listDroplets(any())).willReturn(Mono.just(listApplicationDropletsResponse));
        given(this.client.droplets()).willReturn(this.droplets);
        given(this.droplets.get(any())).willReturn(Mono.just(getDropletResponse));
        given(this.client.tasks()).willReturn(this.tasks);
        given(this.tasks.create(any())).willReturn(Mono.just(createTaskResponse));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        AppDeploymentRequest request = new AppDeploymentRequest(definition, new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"));
        String launch = this.launcher.launch(request);

        // then
        assertThat(launch, equalTo(taskleltId.toString()));
    }

    @Test
    @Ignore
    public void testLaunchTaskAppDoesntExists() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();
        UUID taskleltId = UUID.randomUUID();

        ListApplicationsResponse emptyListApplicationsResponse = ListApplicationsResponse.builder()
            .build();
        ListApplicationsResponse listApplicationsResponse = ListApplicationsResponse.builder()
            .resource(ApplicationResource.builder()
                .id(applicationId.toString())
                .build())
            .build();
        ListSpacesResponse listSpacesResponse = ListSpacesResponse.builder()
            .resource(SpaceResource.builder()
                .entity(SpaceEntity.builder()
                    .name("dev")
                    .organizationId(organizationId.toString())
                    .build())
                .metadata(Metadata.builder()
                    .id(spaceId.toString())
                    .build())
                .build())
            .totalPages(1)
            .build();
        CreateApplicationResponse createApplicationResponse = CreateApplicationResponse.builder()
            .id(applicationId.toString())
            .build();
        CreatePackageResponse createPackageResponse = CreatePackageResponse.builder()
            .id(packageId.toString())
            .build();
        UploadPackageResponse uploadPackageResponse = UploadPackageResponse.builder()
            .id(packageId.toString())
            .build();
        GetPackageResponse getProcessingPackageResponse = GetPackageResponse.builder()
            .state(org.cloudfoundry.client.v3.packages.State.PROCESSING_UPLOAD)
            .id(packageId.toString())
            .build();
        GetPackageResponse getReadyPackageResponse = GetPackageResponse.builder()
            .state(org.cloudfoundry.client.v3.packages.State.READY)
            .id(packageId.toString())
            .build();
        StagePackageResponse stagePackageResponse = StagePackageResponse.builder()
            .id(dropletId.toString())
            .build();
        ListApplicationDropletsResponse listApplicationDropletsResponse = ListApplicationDropletsResponse.builder()
            .resource(DropletResource.builder()
                .id(dropletId.toString())
                .build())
            .build();
        GetDropletResponse getDropletResponse = GetDropletResponse.builder()
            .result(StagedResult.builder()
                .processType("web", "java -jar foo.jar")
                .build())
            .state(org.cloudfoundry.client.v3.droplets.State.STAGED)
            .build();
        CreateTaskResponse createTaskResponse = CreateTaskResponse.builder()
            .id(taskleltId.toString())
            .build();

        given(this.client.applicationsV3()).willReturn(this.applicationsV3);
        given(this.applicationsV3.list(any())).willReturn(Mono.just(emptyListApplicationsResponse), Mono.just(listApplicationsResponse));
        given(this.applicationsV3.listDroplets(any())).willReturn(Mono.just(listApplicationDropletsResponse));
        given(this.client.spaces()).willReturn(this.spaces);
        given(this.spaces.list(any())).willReturn(Mono.just(listSpacesResponse));
        given(this.applicationsV3.create(any())).willReturn(Mono.just(createApplicationResponse));
        given(this.client.packages()).willReturn(this.packages);
        given(this.packages.create(any())).willReturn(Mono.just(createPackageResponse));
        given(this.packages.upload(any())).willReturn(Mono.just(uploadPackageResponse));
        given(this.packages.get(GetPackageRequest.builder().packageId(packageId.toString()).build())).willReturn(Mono.just(getReadyPackageResponse));
        given(this.packages.stage(any())).willReturn(Mono.just(stagePackageResponse));
        given(this.client.droplets()).willReturn(this.droplets);
        given(this.droplets.get(any())).willReturn(Mono.just(getDropletResponse));
        given(this.operations.services()).willReturn(this.services);
        given(this.services.listInstances()).willReturn(Flux.empty());
        given(this.client.tasks()).willReturn(this.tasks);
        given(this.tasks.create(any())).willReturn(Mono.just(createTaskResponse));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        Map<String,String> environmentProperties = new HashMap<>();
        environmentProperties.put("organization", "org");
        environmentProperties.put("space", "dev");

        AppDeploymentRequest request = new AppDeploymentRequest(definition,
            new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"),
            environmentProperties);
        String launch = this.launcher.launch(request);

        // then
        assertThat(launch, equalTo(taskleltId.toString()));
    }

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
