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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.spaces.ListSpacesRequest;
import org.cloudfoundry.client.v3.Lifecycle;
import org.cloudfoundry.client.v3.Relationship;
import org.cloudfoundry.client.v3.applications.Application;
import org.cloudfoundry.client.v3.applications.CreateApplicationRequest;
import org.cloudfoundry.client.v3.applications.DeleteApplicationRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationDropletsRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationDropletsResponse;
import org.cloudfoundry.client.v3.applications.ListApplicationsRequest;
import org.cloudfoundry.client.v3.applications.ListApplicationsResponse;
import org.cloudfoundry.client.v3.droplets.Droplet;
import org.cloudfoundry.client.v3.droplets.GetDropletRequest;
import org.cloudfoundry.client.v3.packages.CreatePackageRequest;
import org.cloudfoundry.client.v3.packages.GetPackageRequest;
import org.cloudfoundry.client.v3.packages.Package;
import org.cloudfoundry.client.v3.packages.StagePackageRequest;
import org.cloudfoundry.client.v3.packages.UploadPackageRequest;
import org.cloudfoundry.client.v3.servicebindings.CreateServiceBindingRequest;
import org.cloudfoundry.client.v3.tasks.CancelTaskRequest;
import org.cloudfoundry.client.v3.tasks.CreateTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskRequest;
import org.cloudfoundry.client.v3.tasks.GetTaskResponse;
import org.cloudfoundry.client.v3.tasks.Task;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.services.ServiceInstance;
import org.cloudfoundry.util.PaginationUtils;
import org.cloudfoundry.util.ResourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.LaunchState;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static java.lang.Integer.parseInt;
import static java.lang.String.valueOf;
import static org.cloudfoundry.util.DelayUtils.exponentialBackOff;
import static org.cloudfoundry.util.tuple.TupleUtils.function;
import static org.springframework.util.StringUtils.commaDelimitedListToSet;

/**
 * @author Greg Turnquist
 * @author Michael Minella
 */
public class CloudFoundryTaskLauncher implements TaskLauncher {

    private static final Logger logger = LoggerFactory
        .getLogger(CloudFoundryTaskLauncher.class);

    private final CloudFoundryClient client;

    private final CloudFoundryOperations operations;

    private final CloudFoundryDeployerProperties properties;

    public static final String SERVICES_PROPERTY_KEY = "spring.cloud.deployer.cloudfoundry.defaults.services";

    public static final String MEMORY_PROPERTY_KEY = "spring.cloud.deployer.cloudfoundry.defaults.memory";

    public static final String DISK_PROPERTY_KEY = "spring.cloud.deployer.cloudfoundry.defaults.disk";

    public CloudFoundryTaskLauncher(CloudFoundryClient client, CloudFoundryOperations operations, CloudFoundryDeployerProperties properties) {
        this.client = client;
        this.operations = operations;
        this.properties = properties;
    }

    @Override
    public void cancel(String id) {

        asyncCancel(id).subscribe();
    }

    /**
     * Set up a reactor pipeline to launch a task. Before launch, check if it exists. If not, deploy. Then launch.
     *
     * @param request
     * @return name of the launched task, returned without waiting for reactor pipeline to complete
     */
    @Override
    public String launch(AppDeploymentRequest request) {

        asyncLaunch(request);

        /*
         * The blocking API does NOT wait for async operations to complete before
         * returning
         */
        return request.getDefinition().getName();
    }

    /**
     * Lookup the current status based on task id.
     *
     * @param id
     * @return
     */
    @Override
    public TaskStatus status(String id) {

        return asyncStatus(id).get(Duration.ofSeconds(30));
    }

    Mono<Void> asyncCancel(String id) {

        return client.applicationsV3()
            .list(ListApplicationsRequest.builder()
                .name(id)
                .page(1)
                .build())
            .log("stream.listApplications")
            .flatMap(response -> Flux.fromIterable(response.getResources()))
            .log("stream.applications")
            .singleOrEmpty()
            .log("stream.singleOrEmpty")
            .map(Application::getId)
            .log("stream.taskIds")
            .then(taskId -> client.tasks()
                .cancel(CancelTaskRequest.builder()
                    .taskId(taskId)
                    .build())
                .log("stream.cancelTask"))
            .after();
    }

    Mono<String> asyncLaunch(AppDeploymentRequest request) {
        return client.applicationsV3().list(ListApplicationsRequest.builder()
                .name(request.getDefinition().getName())
                .page(1)
                .build())
            .log("appsFound")
            .flatMap(applicationsResponse -> processApplication(request, applicationsResponse))
            .single();
        // query for application
        // call splitter function
//        return deploy(request)
//            .log("stream.deploy")
//            .then(applicationId -> bindServices(request, applicationId))
//            .log("stream.bindServices")
//            .then(applicationId -> launchTask(applicationId))
//            .log("stream.launched");
    }

    Mono<String> processApplication(AppDeploymentRequest request, ListApplicationsResponse response) {
        if(response.getResources().size() == 0) {
            System.out.println(">> About to do the deploy");
            return deploy(request)
                .log("processApp1")
                .then(applicationId -> bindServices(request, applicationId))
                .log("processApp2")
                .then(applicationId -> launchTask(applicationId))
                .log("processApp3");
        }
        else {
            return launchTask(response.getResources().get(0).getId());
        }
    }

    Mono<String> bindServices(AppDeploymentRequest request, String applicationId) {
        System.out.println(">> applicationId = " + applicationId);
        return operations.services()
            .listInstances()
            .log("stream.serviceInstances")
            .filter(instance -> servicesToBind(request).contains(instance.getName()))
            .log("stream.filteredInstances")
            .map(ServiceInstance::getId)
            .log("stream.serviceInstanceId")
            .flatMap(serviceInstanceId -> client.serviceBindingsV3()
                .create(CreateServiceBindingRequest.builder()
                    .relationships(CreateServiceBindingRequest.Relationships.builder()
                        .application(Relationship.builder().id(applicationId).build())
                        .serviceInstance(Relationship.builder().id(serviceInstanceId).build())
                        .build())
                    .type(CreateServiceBindingRequest.ServiceBindingType.APP)
                    .build())
                .log("created"))
            .log("stream.serviceBindingCreated")
            .map(a -> applicationId)
            .log("stream.applicationId")
            .single();
    }



    private Set<String> servicesToBind(AppDeploymentRequest request) {
        Set<String> services = new HashSet<>();
        services.addAll(properties.getServices());
        services.addAll(commaDelimitedListToSet(request.getEnvironmentProperties().get(SERVICES_PROPERTY_KEY)));

        return services;
    }

    Mono<TaskStatus> asyncStatus(String id) {

        return client.tasks().get(GetTaskRequest.builder()
                    .taskId(id)
                    .build())
                .map(this::mapTaskToStatus)
                .otherwise(throwable -> {
                    logger.error(throwable.getMessage());
                    return Mono.just(new TaskStatus(id, LaunchState.unknown, null));
                });
    }

    /**
     * Create a new application using supplied {@link AppDeploymentRequest}.
     *
     * @param request
     * @return {@link Mono} containing the newly created {@link Droplet}'s id
     */
    Mono<String> createAndUploadApplication(AppDeploymentRequest request) {

        return createApplication(request.getDefinition().getName(), getSpaceId(request))
            .then(applicationId -> createPackage(applicationId)
                .and(Mono.just(applicationId)))
            .log("packageCreated")
            .then(function((packageId, applicationId) -> uploadPackage(packageId, request)
                .and(Mono.just(applicationId))))
            .log("packageUploaded")
            .then(function((packageId, applicationId) -> waitForPackageProcessing(client, packageId)
                .and(Mono.just(applicationId))))
            .log("waiting for package processing")
            .then(function((packageId, applicationId) -> createDroplet(packageId, request)
                .and(Mono.just(applicationId))))
            .log("droplet created")
            .then(function((dropletId, applicationId) -> waitForDropletProcessing(client, dropletId)
                .and(Mono.just(applicationId))))
            .log("waiting for droplet processing")
            .map(function((dropletId, applicationId) -> applicationId))
            .log("application done...");
    }

    private static Mono<String> waitForDropletProcessing(CloudFoundryClient cloudFoundryClient, String dropletId) {
        return cloudFoundryClient.droplets()
            .get(GetDropletRequest.builder()
                .dropletId(dropletId)
                .build())
            .log("stream.waitingForDroplet")
            .where(response -> !response.getState().equals("PENDING"))
            .repeatWhenEmpty(50, exponentialBackOff(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(10)))
            .map(response -> dropletId);
    }

    private static Mono<String> waitForPackageProcessing(CloudFoundryClient cloudFoundryClient, String packageId) {
        return cloudFoundryClient.packages()
            .get(GetPackageRequest.builder()
                .packageId(packageId)
                .build())
            .where(response -> response.getState().equals("READY"))
            .repeatWhenEmpty(50, exponentialBackOff(Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofMinutes(10)))
            .map(response -> packageId);
    }

    /**
     * Create a new Cloud Foundry application by name
     *
     * @param name
     * @param spaceId
     * @return applicationId
     */
    Mono<String> createApplication(String name, Mono<String> spaceId) {

        return spaceId
            .flatMap(spaceId2 -> client.applicationsV3()
                .create(CreateApplicationRequest.builder()
                    .name(name)
                    .lifecycle(Lifecycle.builder().type("buildpack").data("buildpack", properties.getBuildpack()).build())
                    .relationship("space", Relationship.builder()
                        .id(spaceId2)
                        .build())
                    .build()))
            .single()
            .log("stream.createApplication")
            .map(Application::getId)
            .log("stream.getApplicationId");
    }

    /**
     * Create Cloud Foundry package by applicationId
     *
     * @param applicationId
     * @return packageId
     */
    Mono<String> createPackage(String applicationId) {

        return client.packages()
            .create(CreatePackageRequest.builder()
                .applicationId(applicationId)
                .type(CreatePackageRequest.PackageType.BITS)
                .build())
            .log("stream.createPackage")
            .map(Package::getId)
            .log("stream.getPackageId");
    }

    /**
     * Create an application with a package, then upload the bits into a staging.
     *
     * @param request
     * @return {@link Mono} with the applicationId
     */
    Mono<String> deploy(AppDeploymentRequest request) {
        return getApplicationId(client, request.getDefinition().getName())
            .then(applicationId -> getReadyApplicationId(client, applicationId)
                .otherwiseIfEmpty(deleteExistingApplication(client, applicationId)))
            .otherwiseIfEmpty(createAndUploadApplication(request));
    }

    Mono<String> getSpaceId(AppDeploymentRequest request) {

        return Mono
            .just(request.getEnvironmentProperties().get("organization"))
            .flatMap(organization -> PaginationUtils
                .requestResources(page -> client.spaces()
                    .list(ListSpacesRequest.builder()
                        .name(request.getEnvironmentProperties().get("space"))
                        .page(page)
                        .build())))
            .log("stream.listSpaces")
            .single()
            .log("stream.space")
            .map(ResourceUtils::getId)
            .log("stream.spaceId")
            .cache()
            .log("stream.cacheSpaceId");
    }

    /**
     * Create a new {@link Task} based on applicationId.
     *
     * @param applicationId
     * @return {@link Mono} containing name of the task that was launched
     */
    Mono<String> launchTask(String applicationId) {
        return getDroplet(applicationId)
            .log("lauching.gotDroplet")
            .then(droplet -> createTask(droplet, applicationId));
    }

    Mono<String> createTask(ListApplicationDropletsResponse.Resource resource, String applicationId) {
        // Commented out because I was getting an NPE here:
//        System.out.println(">> command = " + ((Map<String, Object>) resource.getResults().get("process_types")).get("web"));
        return client.tasks()
            .create(CreateTaskRequest.builder()
                .applicationId(applicationId)
                .dropletId(resource.getId())
                .name("timestamp")
//                .command((String) ((Map<String, Object>) resource.getResults().get("process_types")).get("web"))
                .command("eval exec $PWD/.java-buildpack/open_jdk_jre/bin/java -Xmx2048m -Xms1024m -cp . org.springframework.boot.loader.JarLauncher --spring.profiles.active=cloud")
//                .command("eval exec $PWD/.java-buildpack/open_jdk_jre/bin/java -Xmx2048m -Xms1024m -cp . org.springframework.boot.loader.JarLauncher")
                .build())
            .log("stream.createTask")
            .map(Task::getId)
            .log("stream.taskName");
    }

    Mono<ListApplicationDropletsResponse.Resource> getDroplet(String applicationId) {
        return client.applicationsV3()
            .listDroplets(ListApplicationDropletsRequest.builder()
                .applicationId(applicationId)
                .build())
            .log("stream.listDroplet")
            .flatMap(response -> Flux.fromIterable(response.getResources()))
            .single();
    }

    /**
     * Upload bits to a Cloud Foundry application by packageId.
     *
     * @param packageId
     * @param request
     * @return packageId
     */
    Mono<String> uploadPackage(String packageId, AppDeploymentRequest request) {

        try {
            return client.packages()
                .upload(UploadPackageRequest.builder()
                    .packageId(packageId)
                    .bits(request.getResource().getInputStream())
                    .build())
                .log("stream.uploadPackage")
                .map(Package::getId)
                .log("stream.uploadedPackageId");
        } catch (IOException e) {
            return Mono.error(e);
        }
    }

    private static Mono<String> deleteExistingApplication(CloudFoundryClient client, String applicationId) {
        return requestDeleteApplication(client, applicationId)
            .after(Mono::empty);
    }

    /**
     * Look up the applicationId for a given app and confine results to 0 or 1 instance
     *
     * @param client
     * @param name
     * @return {@link Mono} with the application's id
     */
    private static Mono<String> getApplicationId(CloudFoundryClient client, String name) {

        return requestListApplications(client, name)
            .singleOrEmpty()
            .map(Application::getId);
    }

    private static Mono<String> getReadyApplicationId(CloudFoundryClient client, String applicationId) {
        return requestApplicationDroplets(client, applicationId)
            .filter(resource -> "STAGED" .equals(resource.getState()))
            .next()
            .map(resource -> applicationId);
    }

    private static Flux<ListApplicationDropletsResponse.Resource> requestApplicationDroplets(CloudFoundryClient client, String applicationId) {
        return client.applicationsV3()
            .listDroplets(ListApplicationDropletsRequest.builder()
                .applicationId(applicationId)
                .page(1)
                .build())
            .flatMap(response -> Flux.fromIterable(response.getResources()));
    }

    private static Mono<Void> requestDeleteApplication(CloudFoundryClient client, String applicationId) {
        return client.applicationsV3()
            .delete(DeleteApplicationRequest.builder()
                .applicationId(applicationId)
                .build());
    }

    /**
     * List ALL application entries filtered to the provided name
     *
     * @param client
     * @param name
     * @return {@link Flux} of application resources {@link ListApplicationsResponse.Resource}
     */
    private static Flux<ListApplicationsResponse.Resource> requestListApplications(
        CloudFoundryClient client, String name) {

        return client.applicationsV3()
            .list(ListApplicationsRequest.builder()
                .name(name)
                .page(1)
                .build())
            .log("stream.listApplications")
            .flatMap(response -> Flux.fromIterable(response.getResources()))
            .log("stream.applications");
    }

    /**
     * Create a new {@link Droplet} based upon packageId.
     *
     * @param packageId
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

        return client.packages()
            .stage(StagePackageRequest.builder()
                .packageId(packageId)
                .diskLimit(diskQuota(appDeploymentRequest))
                .memoryLimit(memory(appDeploymentRequest))
                .environmentVariables(environmentVariables)
                .build())
            .log("stream.stageDroplet")
            .map(Droplet::getId)
            .log("stream.dropletId");
    }

    private int diskQuota(AppDeploymentRequest request) {
        return parseInt(
            request.getEnvironmentProperties().getOrDefault(DISK_PROPERTY_KEY, valueOf(properties.getDisk())));
    }

    private int memory(AppDeploymentRequest request) {
        return parseInt(
            request.getEnvironmentProperties().getOrDefault(MEMORY_PROPERTY_KEY, valueOf(properties.getMemory())));
    }

    private TaskStatus mapTaskToStatus(GetTaskResponse getTaskResponse) {

        switch (getTaskResponse.getState()) {
            case Task.SUCCEEDED_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.complete, null);
            case Task.RUNNING_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.running, null);
            case Task.PENDING_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.launching, null);
            case Task.CANCELING_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.cancelled, null);
            case Task.FAILED_STATE:
                return new TaskStatus(getTaskResponse.getId(), LaunchState.failed, null);
            default:
                throw new IllegalStateException(
                    "Unsupported CF task state " + getTaskResponse.getState());
        }
    }

}
