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

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.spaces.ListSpacesRequest;
import org.cloudfoundry.client.v3.BuildpackData;
import org.cloudfoundry.client.v3.Lifecycle;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.Type;
import org.cloudfoundry.client.v3.applications.Application;
import org.cloudfoundry.client.v3.applications.ApplicationResource;
import org.cloudfoundry.client.v3.applications.CreateApplicationRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationDropletsRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationDropletsResponse;
import org.cloudfoundry.client.v3.applications.ListApplicationsRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationsResponse;
import org.cloudfoundry.client.v3.droplets.Droplet;
import org.cloudfoundry.client.v3.droplets.DropletResource;
import org.cloudfoundry.client.v3.droplets.GetDropletRequest;
import org.cloudfoundry.client.v3.droplets.GetDropletResponse;
import org.cloudfoundry.client.v3.droplets.StagedResult;
import org.cloudfoundry.client.v3.packages.CreatePackageRequest;
import org.cloudfoundry.client.v3.packages.GetPackageRequest;
import org.cloudfoundry.client.v3.packages.Package;
import org.cloudfoundry.client.v3.packages.PackageType;
import org.cloudfoundry.client.v3.packages.StagePackageRequest;
import org.cloudfoundry.client.v3.packages.State;
import org.cloudfoundry.client.v3.packages.UploadPackageRequest;
import org.cloudfoundry.client.v3.servicebindings.CreateServiceBindingRequest;
import org.cloudfoundry.client.v3.servicebindings.Relationships;
import org.cloudfoundry.client.v3.servicebindings.ServiceBindingType;
import org.cloudfoundry.client.v3.tasks.CancelTaskRequest;
import org.cloudfoundry.client.v3.tasks.CancelTaskResponse;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskResponse;
import org.cloudfoundry.client.v3.tasks.GetTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.cloudfoundry.client.v3.tasks.Task;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.services.ServiceInstance;
import org.cloudfoundry.util.PaginationUtils;
import org.cloudfoundry.util.ResourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;

import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static org.cloudfoundry.util.DelayUtils.exponentialBackOff;
import static org.cloudfoundry.util.tuple.TupleUtils.function;
import static org.springframework.util.StringUtils.commaDelimitedListToSet;

/**
 * {@link TaskLauncher} implementation for CloudFoundry.  When a task is launched, if it has not previously been
 * deployed, the app is created, the package is uploaded, and the droplet is created before launching the actual
 * task.  If the app has been deployed previously, the app/package/droplet is reused and a new task is created.
 *
 * @author Greg Turnquist
 * @author Michael Minella
 */
public class CloudFoundryTaskLauncher implements TaskLauncher {

    private static final Logger logger = LoggerFactory
        .getLogger(CloudFoundryTaskLauncher.class);

    private final CloudFoundryClient client;

    private final CloudFoundryOperations operations;

    private final CloudFoundryDeployerProperties properties;

    private long timeout = 30L;

    public CloudFoundryTaskLauncher(CloudFoundryClient client, CloudFoundryOperations operations, CloudFoundryDeployerProperties properties) {
        this.client = client;
        this.operations = operations;
        this.properties = properties;
    }

    /**
     * @param timeout timeout in seconds for blocking events (status and launch).
     */
    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    /**
     * Setup a reactor pipeline to cancel a running task.
     *
     * @param id the task's id to be cancled as returned from the {@link TaskLauncher#launch(AppDeploymentRequest)}
     */
    @Override
    public void cancel(String id) {

        asyncCancel(id).block(Duration.ofSeconds(this.timeout));
    }

    /**
     * Set up a reactor pipeline to launch a task. Before launch, check if it exists. If not, deploy. Then launch.
     *
     * @param request description of the application to be launched
     * @return name of the launched task, returned without waiting for reactor pipeline to complete
     */
    @Override
    public String launch(AppDeploymentRequest request) {
        return asyncLaunch(request).block(Duration.ofSeconds(this.timeout));
    }

    /**
     * Lookup the current status based on task id.
     *
     * @param id taskId as returned from the {@link TaskLauncher#launch(AppDeploymentRequest)}
     * @return the current task status
     */
    @Override
    public TaskStatus status(String id) {

        return asyncStatus(id).block(Duration.ofSeconds(this.timeout));
    }

    /**
     * Issues an async request to cancel a running task
     *
     * @param id the id of the task to be canceled
     * @return A Mono that will return the results of the cancel request.
     */
    protected Mono<CancelTaskResponse> asyncCancel(String id) {

        return this.client.tasks().cancel(CancelTaskRequest.builder()
            .taskId(id)
            .build());
    }

    /**
     * Issues an async request to launch a task
     *
     * @param request an {@link AppDeploymentRequest} containing the details of the task to be launched
     * @return A Mono that will return the id of the task launched
     */
    protected Mono<String> asyncLaunch(AppDeploymentRequest request) {
        return PaginationUtils.requestClientV3Resources(page -> this.client.applicationsV3().list(ListApplicationsRequest.builder()
                .name(request.getDefinition().getName())
                .page(page)
                .build()))
            .singleOrEmpty()
            .doOnError(e -> logger.error(String.format("Error obtaining app %s", request.getDefinition().getName()), e))
            .then(applicationResource -> processApplication(request, applicationResource))
                .otherwiseIfEmpty(processApplication(request));
    }

    /**
     * Issues an async request for the status of a task
     *
     * @param id the id of the task to query about
     * @return A Mono that will return the task's status
     */
    protected Mono<TaskStatus> asyncStatus(String id) {

        return this.client.tasks().get(GetTaskRequest.builder()
            .taskId(id)
            .build())
            .map(this::mapTaskToStatus)
            .otherwiseIfEmpty(Mono.just(new TaskStatus(id, LaunchState.unknown, null)))
            .otherwise(throwable -> {
                logger.error(throwable.getMessage());
                return Mono.just(new TaskStatus(id, LaunchState.unknown, null));
            });
    }

    /**
     * If an app has been deployed before, the task is launched.  If not, the app is
     * deployed then launched as a task.
     *
     * @param request {@link AppDeploymentRequest} describing app to be deployed
     * @param resource {@link ListApplicationsResponse} with the previously deployed app or empty
     * @return A Mono that will return the task's id
     */
    protected Mono<String> processApplication(AppDeploymentRequest request, ApplicationResource resource) {
            return launchTask(resource.getId(), request);
    }

    /**
     * If an app has been deployed before, the task is launched.  If not, the app is
     * deployed then launched as a task.
     *
     * @param request {@link AppDeploymentRequest} describing app to be deployed
     * @return A Mono that will return the task's id
     */
    protected Mono<String> processApplication(AppDeploymentRequest request) {
        return deploy(request)
            .then(applicationId -> bindServices(request, applicationId))
            .then(applicationId -> launchTask(applicationId, request));
    }

    /**
     * Binds requested services to the app.
     *
     * @param request {@link AppDeploymentRequest} metadata about the services to be bound
     * @param applicationId the id of the app to bind the services to
     * @return A Mono that will return the application id
     */
    protected Mono<String> bindServices(AppDeploymentRequest request, String applicationId) {
        return this.operations.services()
            .listInstances()
            .filter(instance -> servicesToBind(request).contains(instance.getName()))
            .map(ServiceInstance::getId)
            .flatMap(serviceInstanceId -> this.client.serviceBindingsV3()
                .create(CreateServiceBindingRequest.builder()
                    .relationships(Relationships.builder()
                        .application(Relationship.builder().id(applicationId).build())
                        .serviceInstance(Relationship.builder().id(serviceInstanceId).build())
                        .build())
                    .type(ServiceBindingType.APPLICATION)
                    .build()))
            .last()
            .then(Mono.just(applicationId));
    }

    /**
     * Create a new application using supplied {@link AppDeploymentRequest}.
     *
     * @param request The {@link AppDeploymentRequest} containing the details of the app's location
     * @return {@link Mono} containing the newly created {@link Droplet}'s id
     */
    protected Mono<String> createAndUploadApplication(AppDeploymentRequest request) {

        return createApplication(request.getDefinition().getName(), getSpaceId(request))
            .then(applicationId -> createPackage(applicationId)
                .and(Mono.just(applicationId)))
            .then(function((packageId, applicationId) -> uploadPackage(packageId, request)
                .and(Mono.just(applicationId))))
            .then(function((packageId, applicationId) -> waitForPackageProcessing(this.client, packageId)
                .and(Mono.just(applicationId))))
            .then(function((packageId, applicationId) -> createDroplet(packageId, request)
                .and(Mono.just(applicationId))))
            .then(function((dropletId, applicationId) -> waitForDropletProcessing(this.client, dropletId)
                .and(Mono.just(applicationId))))
            .map(function((dropletId, applicationId) -> applicationId));
    }

    /**
     * Create a new Cloud Foundry application by name
     *
     * @param name the name of the Cloud Foundry app to be created
     * @param spaceId the id of the Cloud Foundry space the app is to be created in
     * @return applicationId
     */
    protected Mono<String> createApplication(String name, Mono<String> spaceId) {

        return spaceId
            .then(spaceId2 -> this.client.applicationsV3()
                .create(CreateApplicationRequest.builder()
                    .name(name)
                    .lifecycle(Lifecycle.builder()
                        .type(Type.BUILDPACK)
                        .data(BuildpackData
                            .builder()
                            .buildpack(this.properties.getBuildpack())
                            .build())
                        .build())
                    .relationships(org.cloudfoundry.client.v3.applications.Relationships.builder()
                        .space(Relationship.builder()
                            .id(spaceId2)
                            .build())
                        .build())
                    .build()))
            .map(Application::getId);
    }

    /**
     * Create Cloud Foundry package by applicationId
     *
     * @param applicationId the application's id to create a package for
     * @return packageId
     */
    protected Mono<String> createPackage(String applicationId) {

        return this.client.packages()
            .create(CreatePackageRequest.builder()
                .applicationId(applicationId)
                .type(PackageType.BITS)
                .build())
            .map(Package::getId);
    }

    /**
     * Create an application with a package, then upload the bits into a staging.
     *
     * @param request the {@link AppDeploymentRequest} containing the information about the
     *                app to be deployed
     * @return {@link Mono} with the applicationId
     */
    protected Mono<String> deploy(AppDeploymentRequest request) {
        return getApplicationId(this.client, request.getDefinition().getName())
            .then(applicationId -> getReadyApplicationId(this.client, applicationId))
                .otherwiseIfEmpty(createAndUploadApplication(request));
    }

    /**
     * Returns a Mono that will return the id of the space requested
     *
     * @param request metadata about the space to be deployed
     * @return A Mono that will return the id of the space requested
     */
    protected Mono<String> getSpaceId(AppDeploymentRequest request) {

        return Mono
            .just(request.getDeploymentProperties().get("organization"))
            .flatMap(organization -> PaginationUtils
                .requestClientV2Resources(page -> this.client.spaces()
                    .list(ListSpacesRequest.builder()
                        .name(request.getDeploymentProperties().get("space"))
                        .page(page)
                        .build())))
            .single()
            .map(ResourceUtils::getId)
            .cache();
    }

    /**
     * Create a new {@link Task} based on applicationId.
     *
     * @param applicationId the id of the application the task is to be launched from
     * @return {@link Mono} containing name of the task that was launched
     */
    protected Mono<String> launchTask(String applicationId, AppDeploymentRequest request) {
        return getDroplet(applicationId)
            .map(DropletResource::getId)
            .then(dropletId -> createTask(dropletId, applicationId, request));
    }

    /**
     * Obtains the droplet to be launched as a task and launches it.
     *
     * @param dropletId id of the droplet to be launched
     * @param applicationId id of the app the droplet is associated with
     * @param request metedata about the task to be launched
     * @return A Mono that will return the task's id
     */
    protected Mono<String> createTask(String dropletId, String applicationId, AppDeploymentRequest request) {
        return this.client.droplets()
            .get(GetDropletRequest.builder()
                .dropletId(dropletId)
                .build())
            .then(dropletResponse -> createTaskRequest(dropletResponse, applicationId, request));
    }

    /**
     * Creates an async request to launch a task
     *
     * @param resource droplet to be executed as a task
     * @param applicationId id of the application to be used for the task
     * @param request metadata about the task launch request
     * @return A Mono that will return the id of the task launched
     */
    protected Mono<String> createTaskRequest(GetDropletResponse resource, String applicationId, AppDeploymentRequest request) {
        StringBuilder command = new StringBuilder(((StagedResult) resource.getResult()).getProcessTypes().get("web"));

        String commandLineArgs = request.getCommandlineArguments().stream()
            .collect(Collectors.joining(" "));

        command.append(" ");
        command.append(commandLineArgs);

        return this.client.tasks()
            .create(CreateTaskRequest.builder()
                .applicationId(applicationId)
                .dropletId(resource.getId())
                .name(request.getDefinition().getName())
                .command(command.toString())
                .build())
            .map(CreateTaskResponse::getId);
    }

    /**
     * Obtain the droplet associated with the application id
     *
     * @param applicationId application id for the droplet requested
     * @return A Mono that will return the droplet requested
     */
    protected Mono<DropletResource> getDroplet(String applicationId) {
        return this.client.applicationsV3()
            .listDroplets(ListApplicationDropletsRequest.builder()
                .applicationId(applicationId)
                .build())
            .flatMapIterable(ListApplicationDropletsResponse::getResources)
            .single();
    }

    /**
     * Upload bits to a Cloud Foundry application by packageId.
     *
     * @param packageId id of the package to upload the bits to
     * @param request the {@link AppDeploymentRequest} with information about the location
     *                of the bits
     * @return packageId
     */
    protected Mono<String> uploadPackage(String packageId, AppDeploymentRequest request) {

        try {
            return this.client.packages()
                .upload(UploadPackageRequest.builder()
                    .packageId(packageId)
                    .bits(request.getResource().getInputStream())
                    .build())
                .map(Package::getId);
        } catch (IOException e) {
            return Mono.error(e);
        }
    }

    private Set<String> servicesToBind(AppDeploymentRequest request) {
        Set<String> services = new HashSet<>();
        services.addAll(this.properties.getServices());
        services.addAll(commaDelimitedListToSet(request.getDeploymentProperties().get(CloudFoundryDeployerProperties.SERVICES_PROPERTY_KEY)));

        return services;
    }

    /**
     * Create a new {@link Droplet} based upon packageId.
     *
     * @param packageId The package id the droplet is associated with
     * @return {@link Mono} containing the {@link Droplet}'s ID.
     */
    private Mono<String> createDroplet(String packageId, AppDeploymentRequest appDeploymentRequest) {
        Map<String, String> environmentVariables = new HashMap<>(1);

        try {
            environmentVariables.put("SPRING_APPLICATION_JSON", new ObjectMapper().writeValueAsString(appDeploymentRequest.getDefinition().getProperties()));
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        return this.client.packages()
            .stage(StagePackageRequest.builder()
                .packageId(packageId)
                .stagingDiskInMb(diskQuota(appDeploymentRequest))
                .stagingMemoryInMb(memory(appDeploymentRequest))
                .environmentVariables(environmentVariables)
                .build())
            .map(Droplet::getId);
    }

    private int diskQuota(AppDeploymentRequest request) {
        return parseInt(
            request.getDeploymentProperties().getOrDefault(CloudFoundryDeployerProperties.DISK_PROPERTY_KEY, valueOf(this.properties.getDisk())));
    }

    private int memory(AppDeploymentRequest request) {
        return parseInt(
            request.getDeploymentProperties().getOrDefault(CloudFoundryDeployerProperties.MEMORY_PROPERTY_KEY, valueOf(this.properties.getMemory())));
    }

    private TaskStatus mapTaskToStatus(GetTaskResponse getTaskResponse) {

        switch (getTaskResponse.getState()) {
            case SUCCEEDED_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.complete, null);
            case RUNNING_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.running, null);
            case PENDING_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.launching, null);
            case CANCELING_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.cancelled, null);
            case FAILED_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.failed, null);
            default:
                throw new IllegalStateException(
                    "Unsupported CF task state " + getTaskResponse.getState());
        }
    }

    /**
     * Look up the applicationId for a given app and confine results to 0 or 1 instance
     *
     * @param client a {@link CloudFoundryClient} to work with
     * @param name Name of the application to get the id for
     * @return {@link Mono} with the application's id
     */
    private static Mono<String> getApplicationId(CloudFoundryClient client, String name) {

        return requestListApplications(client, name)
            .singleOrEmpty()
            .map(Application::getId);
    }

    private static Mono<String> getReadyApplicationId(CloudFoundryClient client, String applicationId) {
        return requestApplicationDroplets(client, applicationId)
            .filter(resource -> org.cloudfoundry.client.v3.droplets.State.STAGED.equals(resource.getState()))
            .single()
            .map(resource -> applicationId);
    }

    private static Flux<DropletResource> requestApplicationDroplets(CloudFoundryClient client, String applicationId) {
        return PaginationUtils.requestClientV3Resources(page -> client.applicationsV3()
            .listDroplets(ListApplicationDropletsRequest.builder()
                .applicationId(applicationId)
                .page(page)
                .build()));
    }

    private static Mono<String> waitForDropletProcessing(CloudFoundryClient cloudFoundryClient, String dropletId) {
        return cloudFoundryClient.droplets()
            .get(GetDropletRequest.builder()
                .dropletId(dropletId)
                .build())
            .filter(response -> !response.getState().equals(org.cloudfoundry.client.v3.droplets.State.PENDING))
            .repeatWhenEmpty(50, exponentialBackOff(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(10)))
            .map(response -> dropletId);
    }

    private static Mono<String> waitForPackageProcessing(CloudFoundryClient cloudFoundryClient, String packageId) {
        return cloudFoundryClient.packages()
            .get(GetPackageRequest.builder()
                .packageId(packageId)
                .build())
            .filter(response -> response.getState().equals(State.READY))
            .repeatWhenEmpty(50, exponentialBackOff(Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofMinutes(10)))
            .map(response -> packageId);
    }

    /**
     * List ALL application entries filtered to the provided name
     *
     * @param client {@link CloudFoundryClient} to interact with
     * @param name the name of the applications to obtain
     * @return {@link Flux} of application resources {@link ApplicationResource}
     */
    private static Flux<ApplicationResource> requestListApplications(
        CloudFoundryClient client, String name) {

        return PaginationUtils.requestClientV3Resources(page -> client.applicationsV3()
            .list(ListApplicationsRequest.builder()
                .name(name)
                .page(page)
                .build()));
    }
}
