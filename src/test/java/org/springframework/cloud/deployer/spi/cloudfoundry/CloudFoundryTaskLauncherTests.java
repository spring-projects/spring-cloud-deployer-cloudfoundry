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
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.Metadata;
import org.cloudfoundry.client.v2.organizations.ListOrganizationsResponse;
import org.cloudfoundry.client.v2.organizations.OrganizationResource;
import org.cloudfoundry.client.v2.organizations.Organizations;
import org.cloudfoundry.client.v2.spaces.ListSpacesResponse;
import org.cloudfoundry.client.v2.spaces.SpaceEntity;
import org.cloudfoundry.client.v2.spaces.SpaceResource;
import org.cloudfoundry.client.v2.spaces.Spaces;
import org.cloudfoundry.client.v3.Pagination;
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
import org.cloudfoundry.client.v3.servicebindings.CreateServiceBindingRequest;
import org.cloudfoundry.client.v3.servicebindings.CreateServiceBindingResponse;
import org.cloudfoundry.client.v3.servicebindings.ServiceBindingsV3;
import org.cloudfoundry.client.v3.tasks.CreateTaskResponse;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.cloudfoundry.client.v3.tasks.State;
import org.cloudfoundry.client.v3.tasks.Tasks;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.services.ServiceInstance;
import org.cloudfoundry.operations.services.ServiceInstanceType;
import org.cloudfoundry.operations.services.Services;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import org.springframework.core.io.ClassPathResource;

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
    private Organizations organizations;

    @Mock
    private Packages packages;

    @Mock
    private Services services;

    @Mock
    private ApplicationsV3 applicationsV3;

    @Mock
    private ServiceBindingsV3 serviceBindingsV3;

    private CloudFoundryTaskLauncher launcher;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        CloudFoundryConnectionProperties connectionProperties = new CloudFoundryConnectionProperties();
        connectionProperties.setOrg("org");
        connectionProperties.setSpace("space");

        this.launcher = new CloudFoundryTaskLauncher(this.client, this.operations, connectionProperties, new CloudFoundryDeploymentProperties());
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
        this.launcher.status("bar");
    }

    @Test
    public void testLaunchTaskAppExists() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();
        UUID taskleltId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();

        ListApplicationsResponse listApplicationsResponse = getListApplicationsResponse(applicationId);
        ListApplicationDropletsResponse listApplicationDropletsResponse = getListApplicationDropletsResponse(dropletId);
        GetDropletResponse getDropletResponse = getGetDropletResponse();
        CreateTaskResponse createTaskResponse = getCreateTaskResponse(taskleltId);

        given(this.client.applicationsV3()).willReturn(this.applicationsV3);
        given(this.applicationsV3.list(any())).willReturn(Mono.just(listApplicationsResponse));
        given(this.applicationsV3.listDroplets(any())).willReturn(Mono.just(listApplicationDropletsResponse));
        given(this.client.organizations()).willReturn(this.organizations);
        given(this.client.organizations().list(any())).willReturn(Mono.just(getListOrganizationsResponse(organizationId)));
        given(this.client.droplets()).willReturn(this.droplets);
        given(this.droplets.get(any())).willReturn(Mono.just(getDropletResponse));
        given(this.client.tasks()).willReturn(this.tasks);
        given(this.tasks.create(any())).willReturn(Mono.just(createTaskResponse));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        Map<String, String> deploymentProperties = new HashMap<>(1);
        deploymentProperties.put("organization", "org");
        deploymentProperties.put("space", "the final frontier");

        AppDeploymentRequest request = new AppDeploymentRequest(definition, new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"), deploymentProperties);
        String launch = this.launcher.launch(request);

        // then
        assertThat(launch, equalTo(taskleltId.toString()));
    }

    @Test
    public void testLaunchTaskAppDoesntExists() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();
        UUID taskletId = UUID.randomUUID();

        ListApplicationsResponse emptyListApplicationsResponse = getListApplicationsResponse();
        ListApplicationsResponse listApplicationsResponse = getListApplicationsResponse(applicationId);
        ListSpacesResponse listSpacesResponse = getListSpacesResponse(organizationId, spaceId);
        CreateApplicationResponse createApplicationResponse = getCreateApplicationResponse(applicationId);
        CreatePackageResponse createPackageResponse = getCreatePackageResponse(packageId);
        UploadPackageResponse uploadPackageResponse = getUploadPackageResponse(packageId);
        GetPackageResponse getReadyPackageResponse = getGetPackageResponse(packageId);
        StagePackageResponse stagePackageResponse = getStagePackageResponse(dropletId);
        ListApplicationDropletsResponse listApplicationDropletsResponse = getListApplicationDropletsResponse(dropletId);
        GetDropletResponse getDropletResponse = getGetDropletResponse();
        CreateTaskResponse createTaskResponse = getCreateTaskResponse(taskletId);

        given(this.client.applicationsV3()).willReturn(this.applicationsV3);
        given(this.applicationsV3.list(any())).willReturn(Mono.just(emptyListApplicationsResponse), Mono.just(emptyListApplicationsResponse), Mono.just(listApplicationsResponse));
        given(this.applicationsV3.listDroplets(any())).willReturn(Mono.just(listApplicationDropletsResponse));
        given(this.client.organizations()).willReturn(this.organizations);
        given(this.client.organizations().list(any())).willReturn(Mono.just(getListOrganizationsResponse(organizationId)));
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
        assertThat(launch, equalTo(taskletId.toString()));
    }

    @Test(expected = RuntimeException.class)
    public void testLaunchTaskCantGetAppStatus() {

        given(this.client.applicationsV3()).willReturn(this.applicationsV3);
        given(this.applicationsV3.list(any())).willThrow(new RuntimeException("Error getting App status"));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        Map<String,String> environmentProperties = new HashMap<>();
        environmentProperties.put("organization", "org");
        environmentProperties.put("space", "dev");

        AppDeploymentRequest request = new AppDeploymentRequest(definition,
            new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"),
            environmentProperties);

        this.launcher.launch(request);
    }

    @Test(expected = RuntimeException.class)
    public void testLaunchTaskAppCreationFails() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();

        ListApplicationsResponse emptyListApplicationsResponse = getListApplicationsResponse();
        ListApplicationsResponse listApplicationsResponse = getListApplicationsResponse(applicationId);
        ListSpacesResponse listSpacesResponse = getListSpacesResponse(organizationId, spaceId);
        ListApplicationDropletsResponse listApplicationDropletsResponse = getListApplicationDropletsResponse(dropletId);

        given(this.client.applicationsV3()).willReturn(this.applicationsV3);
        given(this.applicationsV3.list(any())).willReturn(Mono.just(emptyListApplicationsResponse), Mono.just(listApplicationsResponse));
        given(this.applicationsV3.listDroplets(any())).willReturn(Mono.just(listApplicationDropletsResponse));
        given(this.client.spaces()).willReturn(this.spaces);
        given(this.spaces.list(any())).willReturn(Mono.just(listSpacesResponse));
        given(this.applicationsV3.create(any())).willThrow(new RuntimeException("Application creation failed"));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        Map<String,String> environmentProperties = new HashMap<>();
        environmentProperties.put("organization", "org");
        environmentProperties.put("space", "dev");

        AppDeploymentRequest request = new AppDeploymentRequest(definition,
            new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"),
            environmentProperties);

        this.launcher.launch(request);
    }

    @Test(expected = RuntimeException.class)
    public void testLaunchTaskCreatePackageFails() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();

        ListApplicationsResponse emptyListApplicationsResponse = getListApplicationsResponse();
        ListApplicationsResponse listApplicationsResponse = getListApplicationsResponse(applicationId);
        ListSpacesResponse listSpacesResponse = getListSpacesResponse(organizationId, spaceId);
        CreateApplicationResponse createApplicationResponse = getCreateApplicationResponse(applicationId);
        ListApplicationDropletsResponse listApplicationDropletsResponse = getListApplicationDropletsResponse(dropletId);

        given(this.client.applicationsV3()).willReturn(this.applicationsV3);
        given(this.applicationsV3.list(any())).willReturn(Mono.just(emptyListApplicationsResponse), Mono.just(listApplicationsResponse));
        given(this.applicationsV3.listDroplets(any())).willReturn(Mono.just(listApplicationDropletsResponse));
        given(this.client.spaces()).willReturn(this.spaces);
        given(this.spaces.list(any())).willReturn(Mono.just(listSpacesResponse));
        given(this.applicationsV3.create(any())).willReturn(Mono.just(createApplicationResponse));
        given(this.client.packages()).willReturn(this.packages);
        given(this.packages.create(any())).willThrow(new RuntimeException("Error creating a package"));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        Map<String,String> environmentProperties = new HashMap<>();
        environmentProperties.put("organization", "org");
        environmentProperties.put("space", "dev");

        AppDeploymentRequest request = new AppDeploymentRequest(definition,
            new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"),
            environmentProperties);

        this.launcher.launch(request);
    }

    @Test(expected = RuntimeException.class)
    public void testLaunchTaskUploadPackageFails() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();

        ListApplicationsResponse emptyListApplicationsResponse = getListApplicationsResponse();
        ListApplicationsResponse listApplicationsResponse = getListApplicationsResponse(applicationId);
        ListSpacesResponse listSpacesResponse = getListSpacesResponse(organizationId, spaceId);
        CreateApplicationResponse createApplicationResponse = getCreateApplicationResponse(applicationId);
        CreatePackageResponse createPackageResponse = getCreatePackageResponse(packageId);
        ListApplicationDropletsResponse listApplicationDropletsResponse = getListApplicationDropletsResponse(dropletId);

        given(this.client.applicationsV3()).willReturn(this.applicationsV3);
        given(this.applicationsV3.list(any())).willReturn(Mono.just(emptyListApplicationsResponse), Mono.just(listApplicationsResponse));
        given(this.applicationsV3.listDroplets(any())).willReturn(Mono.just(listApplicationDropletsResponse));
        given(this.client.spaces()).willReturn(this.spaces);
        given(this.spaces.list(any())).willReturn(Mono.just(listSpacesResponse));
        given(this.applicationsV3.create(any())).willReturn(Mono.just(createApplicationResponse));
        given(this.client.packages()).willReturn(this.packages);
        given(this.packages.create(any())).willReturn(Mono.just(createPackageResponse));
        given(this.packages.upload(any())).willThrow(new RuntimeException("Upload failed"));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        Map<String,String> environmentProperties = new HashMap<>();
        environmentProperties.put("organization", "org");
        environmentProperties.put("space", "dev");

        AppDeploymentRequest request = new AppDeploymentRequest(definition,
            new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"),
            environmentProperties);
        this.launcher.launch(request);
    }

    @Test(expected = RuntimeException.class)
    public void testLaunchTaskPackageStatusFails() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();

        ListApplicationsResponse emptyListApplicationsResponse = getListApplicationsResponse();
        ListApplicationsResponse listApplicationsResponse = getListApplicationsResponse(applicationId);
        ListSpacesResponse listSpacesResponse = getListSpacesResponse(organizationId, spaceId);
        CreateApplicationResponse createApplicationResponse = getCreateApplicationResponse(applicationId);
        CreatePackageResponse createPackageResponse = getCreatePackageResponse(packageId);
        UploadPackageResponse uploadPackageResponse = getUploadPackageResponse(packageId);
        ListApplicationDropletsResponse listApplicationDropletsResponse = getListApplicationDropletsResponse(dropletId);

        given(this.client.applicationsV3()).willReturn(this.applicationsV3);
        given(this.applicationsV3.list(any())).willReturn(Mono.just(emptyListApplicationsResponse), Mono.just(listApplicationsResponse));
        given(this.applicationsV3.listDroplets(any())).willReturn(Mono.just(listApplicationDropletsResponse));
        given(this.client.spaces()).willReturn(this.spaces);
        given(this.spaces.list(any())).willReturn(Mono.just(listSpacesResponse));
        given(this.applicationsV3.create(any())).willReturn(Mono.just(createApplicationResponse));
        given(this.client.packages()).willReturn(this.packages);
        given(this.packages.create(any())).willReturn(Mono.just(createPackageResponse));
        given(this.packages.upload(any())).willReturn(Mono.just(uploadPackageResponse));
        given(this.packages.get(GetPackageRequest.builder().packageId(packageId.toString()).build())).willThrow(new RuntimeException("Package Status Failed"));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        Map<String,String> environmentProperties = new HashMap<>();
        environmentProperties.put("organization", "org");
        environmentProperties.put("space", "dev");

        AppDeploymentRequest request = new AppDeploymentRequest(definition,
            new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"),
            environmentProperties);
        this.launcher.launch(request);
    }

    @Test(expected = RuntimeException.class)
    public void testLaunchTaskCreateDropletFails() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();

        ListApplicationsResponse emptyListApplicationsResponse = getListApplicationsResponse();
        ListApplicationsResponse listApplicationsResponse = getListApplicationsResponse(applicationId);
        ListSpacesResponse listSpacesResponse = getListSpacesResponse(organizationId, spaceId);
        CreateApplicationResponse createApplicationResponse = getCreateApplicationResponse(applicationId);
        CreatePackageResponse createPackageResponse = getCreatePackageResponse(packageId);
        UploadPackageResponse uploadPackageResponse = getUploadPackageResponse(packageId);
        GetPackageResponse getReadyPackageResponse = getGetPackageResponse(packageId);
        ListApplicationDropletsResponse listApplicationDropletsResponse = getListApplicationDropletsResponse(dropletId);

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
        given(this.packages.stage(any())).willThrow(new RuntimeException("Droplet was not created"));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        Map<String,String> environmentProperties = new HashMap<>();
        environmentProperties.put("organization", "org");
        environmentProperties.put("space", "dev");

        AppDeploymentRequest request = new AppDeploymentRequest(definition,
            new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"),
            environmentProperties);

        this.launcher.launch(request);
    }

    @Test
    public void testLaunchTaskOneServiceToBind() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();
        UUID taskletId = UUID.randomUUID();
        UUID serviceInstanceId = UUID.randomUUID();

        ListApplicationsResponse emptyListApplicationsResponse = getListApplicationsResponse();
        ListApplicationsResponse listApplicationsResponse = getListApplicationsResponse(applicationId);
        ListSpacesResponse listSpacesResponse = getListSpacesResponse(organizationId, spaceId);
        CreateApplicationResponse createApplicationResponse = getCreateApplicationResponse(applicationId);
        CreatePackageResponse createPackageResponse = getCreatePackageResponse(packageId);
        UploadPackageResponse uploadPackageResponse = getUploadPackageResponse(packageId);
        GetPackageResponse getReadyPackageResponse = getGetPackageResponse(packageId);
        StagePackageResponse stagePackageResponse = getStagePackageResponse(dropletId);
        ListApplicationDropletsResponse listApplicationDropletsResponse = getListApplicationDropletsResponse(dropletId);
        GetDropletResponse getDropletResponse = getGetDropletResponse();
        CreateTaskResponse createTaskResponse = getCreateTaskResponse(taskletId);
        Flux<ServiceInstance> serviceInstances = Flux.just(ServiceInstance.builder()
                .id(serviceInstanceId.toString())
                .name("my_mysql")
                .type(ServiceInstanceType.MANAGED)
                .build(),
        ServiceInstance.builder()
            .id(UUID.randomUUID().toString())
            .name("my_other_service")
            .type(ServiceInstanceType.MANAGED)
            .build());
        CreateServiceBindingResponse createServiceBindingResponse = CreateServiceBindingResponse.builder()
            .build();

        given(this.client.applicationsV3()).willReturn(this.applicationsV3);
        given(this.applicationsV3.list(any())).willReturn(Mono.just(emptyListApplicationsResponse), Mono.just(emptyListApplicationsResponse), Mono.just(listApplicationsResponse));
        given(this.applicationsV3.listDroplets(any())).willReturn(Mono.just(listApplicationDropletsResponse));
        given(this.client.organizations()).willReturn(this.organizations);
        given(this.client.organizations().list(any())).willReturn(Mono.just(getListOrganizationsResponse(organizationId)));
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
        given(this.services.listInstances()).willReturn(serviceInstances);
        given(this.client.serviceBindingsV3()).willReturn(this.serviceBindingsV3);

        ArgumentCaptor<CreateServiceBindingRequest> createServiceBindingRequestArgumentCaptor =
            ArgumentCaptor.forClass(CreateServiceBindingRequest.class);

        given(this.serviceBindingsV3.create(createServiceBindingRequestArgumentCaptor.capture())).willReturn(Mono.just(createServiceBindingResponse));
        given(this.client.tasks()).willReturn(this.tasks);
        given(this.tasks.create(any())).willReturn(Mono.just(createTaskResponse));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        Map<String,String> environmentProperties = new HashMap<>();
        environmentProperties.put("organization", "org");
        environmentProperties.put("space", "dev");
        environmentProperties.put(CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY, "my_mysql");

        AppDeploymentRequest request = new AppDeploymentRequest(definition,
            new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"),
            environmentProperties);
        String launch = this.launcher.launch(request);

        // then
        assertThat(launch, equalTo(taskletId.toString()));

        assertThat(1, equalTo(createServiceBindingRequestArgumentCaptor.getAllValues().size()));

        CreateServiceBindingRequest serviceBindingRequest = createServiceBindingRequestArgumentCaptor.getValue();
        assertThat(applicationId.toString(), equalTo(serviceBindingRequest.getRelationships().getApplication().getId()));
        assertThat(serviceInstanceId.toString(), equalTo(serviceBindingRequest.getRelationships().getServiceInstance().getId()));
    }

    @Test
    public void testLaunchTaskThreeServicesToBind() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();
        UUID taskletId = UUID.randomUUID();
        UUID serviceInstanceId = UUID.randomUUID();

        ListApplicationsResponse emptyListApplicationsResponse = getListApplicationsResponse();
        ListApplicationsResponse listApplicationsResponse = getListApplicationsResponse(applicationId);
        ListSpacesResponse listSpacesResponse = getListSpacesResponse(organizationId, spaceId);
        CreateApplicationResponse createApplicationResponse = getCreateApplicationResponse(applicationId);
        CreatePackageResponse createPackageResponse = getCreatePackageResponse(packageId);
        UploadPackageResponse uploadPackageResponse = getUploadPackageResponse(packageId);
        GetPackageResponse getReadyPackageResponse = getGetPackageResponse(packageId);
        StagePackageResponse stagePackageResponse = getStagePackageResponse(dropletId);
        ListApplicationDropletsResponse listApplicationDropletsResponse = getListApplicationDropletsResponse(dropletId);
        GetDropletResponse getDropletResponse = getGetDropletResponse();
        CreateTaskResponse createTaskResponse = getCreateTaskResponse(taskletId);
        Flux<ServiceInstance> serviceInstances = Flux.just(ServiceInstance.builder()
                .id("my_service3" + serviceInstanceId.toString())
                .name("my_service3")
                .type(ServiceInstanceType.MANAGED)
                .build(),
            ServiceInstance.builder()
                .id("my_service1" + serviceInstanceId.toString())
                .name("my_service1")
                .type(ServiceInstanceType.MANAGED)
                .build(),
            ServiceInstance.builder()
                .id("my_service2" + serviceInstanceId.toString())
                .name("my_service2")
                .type(ServiceInstanceType.MANAGED)
                .build());
        CreateServiceBindingResponse createServiceBindingResponse = CreateServiceBindingResponse.builder()
            .build();

        given(this.client.applicationsV3()).willReturn(this.applicationsV3);
        given(this.applicationsV3.list(any())).willReturn(Mono.just(emptyListApplicationsResponse), Mono.just(emptyListApplicationsResponse), Mono.just(listApplicationsResponse));
        given(this.applicationsV3.listDroplets(any())).willReturn(Mono.just(listApplicationDropletsResponse));
        given(this.client.organizations()).willReturn(this.organizations);
        given(this.client.organizations().list(any())).willReturn(Mono.just(getListOrganizationsResponse(organizationId)));
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
        given(this.services.listInstances()).willReturn(serviceInstances);
        given(this.client.serviceBindingsV3()).willReturn(this.serviceBindingsV3);

        ArgumentCaptor<CreateServiceBindingRequest> createServiceBindingRequestArgumentCaptor =
            ArgumentCaptor.forClass(CreateServiceBindingRequest.class);

        given(this.serviceBindingsV3.create(createServiceBindingRequestArgumentCaptor.capture())).willReturn(Mono.just(createServiceBindingResponse));
        given(this.client.tasks()).willReturn(this.tasks);
        given(this.tasks.create(any())).willReturn(Mono.just(createTaskResponse));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        Map<String,String> environmentProperties = new HashMap<>();
        environmentProperties.put("organization", "org");
        environmentProperties.put("space", "dev");
        environmentProperties.put(CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY, "my_service1,my_service2,my_service3");

        AppDeploymentRequest request = new AppDeploymentRequest(definition,
            new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"),
            environmentProperties);
        String launch = this.launcher.launch(request);

        // then
        assertThat(launch, equalTo(taskletId.toString()));

        assertThat(3, equalTo(createServiceBindingRequestArgumentCaptor.getAllValues().size()));

        List<CreateServiceBindingRequest> serviceBindingRequests = createServiceBindingRequestArgumentCaptor.getAllValues();
        Set<String> serviceIds = new HashSet<>(3);

        for (CreateServiceBindingRequest serviceBindingRequest : serviceBindingRequests) {
            assertThat(applicationId.toString(), equalTo(serviceBindingRequest.getRelationships().getApplication().getId()));
            serviceIds.add(serviceBindingRequest.getRelationships().getServiceInstance().getId());
        }

        assertThat(3, equalTo(serviceIds.size()));
    }

    @Test(expected = RuntimeException.class)
    public void testLaunchTaskServiceBindFails() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();
        UUID serviceInstanceId = UUID.randomUUID();

        ListApplicationsResponse emptyListApplicationsResponse = getListApplicationsResponse();
        ListApplicationsResponse listApplicationsResponse = getListApplicationsResponse(applicationId);
        ListSpacesResponse listSpacesResponse = getListSpacesResponse(organizationId, spaceId);
        CreateApplicationResponse createApplicationResponse = getCreateApplicationResponse(applicationId);
        CreatePackageResponse createPackageResponse = getCreatePackageResponse(packageId);
        UploadPackageResponse uploadPackageResponse = getUploadPackageResponse(packageId);
        GetPackageResponse getReadyPackageResponse = getGetPackageResponse(packageId);
        StagePackageResponse stagePackageResponse = getStagePackageResponse(dropletId);
        ListApplicationDropletsResponse listApplicationDropletsResponse = getListApplicationDropletsResponse(dropletId);
        GetDropletResponse getDropletResponse = getGetDropletResponse();
        Flux<ServiceInstance> serviceInstances = Flux.just(ServiceInstance.builder()
                .id("my_service3" + serviceInstanceId.toString())
                .name("my_service3")
                .type(ServiceInstanceType.MANAGED)
                .build(),
            ServiceInstance.builder()
                .id("my_service1" + serviceInstanceId.toString())
                .name("my_service1")
                .type(ServiceInstanceType.MANAGED)
                .build(),
            ServiceInstance.builder()
                .id("my_service2" + serviceInstanceId.toString())
                .name("my_service2")
                .type(ServiceInstanceType.MANAGED)
                .build());
        CreateServiceBindingResponse createServiceBindingResponse = CreateServiceBindingResponse.builder()
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
        given(this.services.listInstances()).willReturn(serviceInstances);
        given(this.client.serviceBindingsV3()).willReturn(this.serviceBindingsV3);

        ArgumentCaptor<CreateServiceBindingRequest> createServiceBindingRequestArgumentCaptor =
            ArgumentCaptor.forClass(CreateServiceBindingRequest.class);

        given(this.serviceBindingsV3.create(createServiceBindingRequestArgumentCaptor.capture())).willReturn(Mono.just(createServiceBindingResponse), Mono.error(new RuntimeException("Service binding failed")));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        Map<String,String> environmentProperties = new HashMap<>();
        environmentProperties.put("organization", "org");
        environmentProperties.put("space", "dev");
        environmentProperties.put(CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY, "my_service1,my_service2,my_service3");

        AppDeploymentRequest request = new AppDeploymentRequest(definition,
            new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"),
            environmentProperties);

        this.launcher.launch(request);
    }

    @Test(expected = RuntimeException.class)
    public void testLaunchTaskCreateTaskFails() {
        // given
        UUID applicationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        UUID dropletId = UUID.randomUUID();

        ListApplicationsResponse emptyListApplicationsResponse = getListApplicationsResponse();
        ListApplicationsResponse listApplicationsResponse = getListApplicationsResponse(applicationId);
        ListSpacesResponse listSpacesResponse = getListSpacesResponse(organizationId, spaceId);
        CreateApplicationResponse createApplicationResponse = getCreateApplicationResponse(applicationId);
        CreatePackageResponse createPackageResponse = getCreatePackageResponse(packageId);
        UploadPackageResponse uploadPackageResponse = getUploadPackageResponse(packageId);
        GetPackageResponse getReadyPackageResponse = getGetPackageResponse(packageId);
        StagePackageResponse stagePackageResponse = getStagePackageResponse(dropletId);
        ListApplicationDropletsResponse listApplicationDropletsResponse = getListApplicationDropletsResponse(dropletId);
        GetDropletResponse getDropletResponse = getGetDropletResponse();

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
        given(this.tasks.create(any())).willThrow(new RuntimeException("Task Create failed"));

        // when
        AppDefinition definition = new AppDefinition("foo", null);

        Map<String,String> environmentProperties = new HashMap<>();
        environmentProperties.put("organization", "org");
        environmentProperties.put("space", "dev");

        AppDeploymentRequest request = new AppDeploymentRequest(definition,
            new ClassPathResource("/org/springframework/cloud/deployer/spi/cloudfoundry/CloudFoundryTaskLauncherTests.class"),
            environmentProperties);

        this.launcher.launch(request);
    }

    private CreateTaskResponse getCreateTaskResponse(UUID taskleltId) {
        return CreateTaskResponse.builder()
            .id(taskleltId.toString())
            .build();
    }

    private GetDropletResponse getGetDropletResponse() {
        return GetDropletResponse.builder()
            .result(StagedResult.builder()
                .processType("web", "java -jar foo.jar")
                .build())
            .state(org.cloudfoundry.client.v3.droplets.State.STAGED)
            .build();
    }

    private ListApplicationDropletsResponse getListApplicationDropletsResponse(UUID dropletId) {
        return ListApplicationDropletsResponse.builder()
            .resource(DropletResource.builder()
                .id(dropletId.toString())
                .build())
            .pagination(Pagination.builder()
                .totalResults(1)
                .totalPages(1)
                .build())
            .build();
    }

    private ListApplicationsResponse getListApplicationsResponse(UUID applicationId) {
        return ListApplicationsResponse.builder()
            .resource(ApplicationResource.builder()
                .id(applicationId.toString())
                .build())
            .pagination(Pagination.builder()
                .totalPages(1)
                .build())
            .build();
    }

    private ListApplicationsResponse getListApplicationsResponse() {
        return ListApplicationsResponse.builder()
            .resources(Collections.emptyList())
            .pagination(Pagination.builder()
                .totalPages(1)
                .build())
            .build();
    }

    private StagePackageResponse getStagePackageResponse(UUID dropletId) {
        return StagePackageResponse.builder()
            .id(dropletId.toString())
            .build();
    }

    private UploadPackageResponse getUploadPackageResponse(UUID packageId) {
        return UploadPackageResponse.builder()
            .id(packageId.toString())
            .build();
    }

    private CreatePackageResponse getCreatePackageResponse(UUID packageId) {
        return CreatePackageResponse.builder()
            .id(packageId.toString())
            .build();
    }

    private CreateApplicationResponse getCreateApplicationResponse(UUID applicationId) {
        return CreateApplicationResponse.builder()
            .id(applicationId.toString())
            .build();
    }

    private ListSpacesResponse getListSpacesResponse(UUID organizationId, UUID spaceId) {
        return ListSpacesResponse.builder()
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
    }

    private GetPackageResponse getGetPackageResponse(UUID packageId) {
        return GetPackageResponse.builder()
            .state(org.cloudfoundry.client.v3.packages.State.READY)
            .id(packageId.toString())
            .build();
    }

    private ListOrganizationsResponse getListOrganizationsResponse(UUID orgId) {
        return ListOrganizationsResponse.builder()
                .resource(OrganizationResource.builder()
                        .metadata(Metadata.builder()
                                .id(orgId.toString())
                        .build())
                .build())
                .build();
    }
}
