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

package org.springframework.cloud.deployer.spi.cloudfoundry

import org.apache.commons.io.IOUtils
import org.cloudfoundry.client.CloudFoundryClient
import org.cloudfoundry.client.v2.spaces.ListSpacesResponse
import org.cloudfoundry.client.v2.spaces.SpaceResource
import org.cloudfoundry.client.v2.spaces.Spaces
import org.cloudfoundry.client.v3.applications.ApplicationsV3
import org.cloudfoundry.client.v3.applications.CreateApplicationResponse
import org.cloudfoundry.client.v3.applications.ListApplicationsResponse
import org.cloudfoundry.client.v3.packages.CreatePackageResponse
import org.cloudfoundry.client.v3.packages.GetPackageResponse
import org.cloudfoundry.client.v3.packages.Packages
import org.cloudfoundry.client.v3.packages.UploadPackageResponse
import org.cloudfoundry.client.v3.tasks.*
import org.springframework.cloud.deployer.spi.core.AppDefinition
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest
import org.springframework.cloud.deployer.spi.task.LaunchState
import org.springframework.core.io.Resource
import reactor.core.publisher.Mono
import spock.lang.Ignore
import spock.lang.Specification
/**
 * @author Greg Turnquist
 */
@Ignore
class CloudFoundryTaskLauncherSpec extends Specification {

    CloudFoundryClient client

    CloudFoundryTaskLauncher launcher

    Resource resource

    ApplicationsV3 applicationsV3
    Packages packages
    Tasks tasks
    Spaces spaces

    def setup() {
        client = Mock(CloudFoundryClient)
        launcher = new CloudFoundryTaskLauncher(client)
        resource = Mock(Resource)

        applicationsV3 = Mock(ApplicationsV3)
        packages = Mock(Packages)
        tasks = Mock(Tasks)
        spaces = Mock(Spaces)
    }

    def "launch non-existing task"() {
        given:
        def appName = 'my-cool-app'

        when:
        def results = launcher.launch(new AppDeploymentRequest(
                new AppDefinition(appName, Collections.emptyMap()),
                resource,
                [
                    organization: "spring-cloud",
                    space: 'production'
                ]
        ))

        then:
        results == appName

        2 * client.applicationsV3() >> { applicationsV3 }
        4 * client.packages() >> { packages }
        1 * client.spaces() >> { spaces }
        0 * client._

        1 * applicationsV3.list(_) >> {
            Mono.just(ListApplicationsResponse.builder().build())
        }
        1 * applicationsV3.create(_) >> {
            Mono.just(CreateApplicationResponse.builder()
                    .id("app-id")
                    .build())
        }
        0 * applicationsV3._

        1 * packages.create(_) >> {
            Mono.just(CreatePackageResponse.builder()
                    .id("package-id")
                    .build())
        }
        1 * packages.upload(_) >> {
            Mono.just(UploadPackageResponse.builder()
                    .id("package-id")
                    .build())
        }
        1 * packages.get(_) >> {
            Mono.just(GetPackageResponse.builder()
                .id("package-id")
                .state("READY")
                .build())
        }
        1 * packages.stage(_) >> {
            Mono.just(StagePackageResponse.builder()
                    .id("package-id")
                    .build())
        }
        0 * packages._

        1 * spaces.list(_) >> {
            Mono.just(ListSpacesResponse.builder()
                .resource(SpaceResource.builder()
                    .metadata(org.cloudfoundry.client.v2.Resource.Metadata.builder()
                        .id("space-id")
                        .build())
                    .build())
                .totalPages(1)
                .build())
        }
        0 * spaces._

        0 * tasks._

        1 * resource.getInputStream() >> { IOUtils.toInputStream("my app's bits") }
        0 * resource._
    }

    def "launch already deployed task"() {
        given:
        def appName = 'my-cool-app'

        when:
        def results = launcher.launch(new AppDeploymentRequest(
                new AppDefinition(appName, Collections.emptyMap()),
                resource))

        then:
        results == appName

        2 * client.applicationsV3() >> { applicationsV3 }
        1 * client.tasks() >> { tasks }
        0 * client._

        2 * applicationsV3.list(_) >> {
            Mono.just(ListApplicationsResponse.builder()
                    .resource(ListApplicationsResponse.Resource.builder()
                    .id("app-id")
                    .name("my-cool-app")
                    .build())
                    .build())
        }
        0 * applicationsV3._

        1 * tasks.create(_) >> {
            Mono.just(CreateTaskResponse.builder()
                    .name(appName)
                    .build())
        }
        0 * tasks._

        0 * packages._
        0 * resource._
    }

    def "launch task with bad bits"() {
        given:
        def appName = 'my-cool-app'

        when:
        launcher.launch(new AppDeploymentRequest(
                new AppDefinition(appName, Collections.emptyMap()),
                resource))

        then:
        def thrown = thrown(RuntimeException)
        thrown.message == "java.io.IOException: Can't find your resource!"

        2 * client.applicationsV3() >> { applicationsV3 }
        2 * client.packages() >> { packages }
        0 * client._

        1 * applicationsV3.list(_) >> {
            Mono.just(ListApplicationsResponse.builder().build())
        }
        1 * applicationsV3.create(_) >> {
            Mono.just(CreateApplicationResponse.builder()
                    .id("app-id")
                    .build())
        }
        0 * applicationsV3._

        1 * packages.create(_) >> {
            Mono.just(CreatePackageResponse.builder()
                    .id("package-id")
                    .build())
        }
        0 * packages._

        0 * tasks._

        1 * resource.getInputStream() >> { throw new IOException("Can't find your resource!") }
        0 * resource._
    }


    def "launch failing task"() {
        given:
        def appName = 'my-cool-app'

        when:
        def results = launcher.launch(new AppDeploymentRequest(
                new AppDefinition(appName, Collections.emptyMap()),
                resource))
        def status = launcher.status(appName)

        then:
        results == appName

        status.taskLaunchId == 'app-id'
        status.state == LaunchState.failed

        3 * client.applicationsV3() >> { applicationsV3 }
        2 * client.tasks() >> { tasks }
        0 * client._

        3 * applicationsV3.list(_) >> {
            Mono.just(ListApplicationsResponse.builder()
                    .resource(ListApplicationsResponse.Resource.builder()
                    .id("app-id")
                    .name("my-cool-app")
                    .build())
                    .build())
        }
        0 * applicationsV3._

        1 * tasks.create(_) >> {
            Mono.just(CreateTaskResponse.builder()
                    .name(appName)
                    .build())
        }
        1 * tasks.get(GetTaskRequest.builder().taskId("app-id").build()) >> {
            Mono.just(GetTaskResponse.builder()
                    .id("app-id")
                    .state(Task.FAILED_STATE)
                    .build())
        }
        0 * tasks._

        0 * packages._
        0 * resource._
    }

    def "cancel a running task"() {
        given:
        def appName = 'my-cool-app'

        when:
        launcher.cancel(appName)
        def status = launcher.status(appName)

        then:
        status.taskLaunchId == 'app-id'
        status.state == LaunchState.cancelled

        2 * client.applicationsV3() >> { applicationsV3 }
        2 * client.tasks() >> { tasks }
        0 * client._

        2 * applicationsV3.list(_) >> {
            Mono.just(ListApplicationsResponse.builder()
                    .resource(ListApplicationsResponse.Resource.builder()
                    .id("app-id")
                    .name("my-cool-app")
                    .build())
                    .build())
        }
        0 * applicationsV3._

        1 * tasks.cancel(CancelTaskRequest.builder().taskId("app-id").build()) >> {
            Mono.just(CancelTaskResponse.builder()
                    .id("app-id")
                    .build())
        }
        1 * tasks.get(GetTaskRequest.builder().taskId("app-id").build()) >> {
            Mono.just(GetTaskResponse.builder()
                    .id("app-id")
                    .state(Task.CANCELING_STATE)
                    .build())
        }
        0 * tasks._

        0 * packages._
        0 * resource._
    }

    def "status a running task"() {
        given:
        def appName = "my-cool-app"

        when:
        def status1 = launcher.status(appName)
        def status2 = launcher.status(appName)
        def status3 = launcher.status(appName)

        then:
        status1.state == LaunchState.launching
        status1.taskLaunchId == 'app-id'

        status2.state == LaunchState.running
        status2.taskLaunchId == 'app-id'

        status3.state == LaunchState.complete
        status3.taskLaunchId == 'app-id'

        3 * client.applicationsV3() >> { applicationsV3 }
        3 * client.tasks() >> { tasks }
        0 * client._

        3 * applicationsV3.list(_) >> {
            Mono.just(ListApplicationsResponse.builder()
                    .resource(ListApplicationsResponse.Resource.builder()
                    .id("app-id")
                    .name("my-cool-app")
                    .build())
                    .build())
        }
        0 * applicationsV3._

        1 * tasks.get(GetTaskRequest.builder().taskId("app-id").build()) >> {
            Mono.just(GetTaskResponse.builder()
                    .id("app-id")
                    .state(Task.PENDING_STATE)
                    .build())
        }
        1 * tasks.get(GetTaskRequest.builder().taskId("app-id").build()) >> {
            Mono.just(GetTaskResponse.builder()
                    .id("app-id")
                    .state(Task.RUNNING_STATE)
                    .build())
        }
        1 * tasks.get(GetTaskRequest.builder().taskId("app-id").build()) >> {
            Mono.just(GetTaskResponse.builder()
                    .id("app-id")
                    .state(Task.SUCCEEDED_STATE)
                    .build())
        }
        0 * tasks._
        0 * packages._
        0 * resource._

    }

    def "status an unknown task"() {
        given:
        def appName = "my-cool-app"

        when:
        def status = launcher.status(appName)

        then:
        def thrown = thrown(IllegalStateException)
        thrown.message == "Unsupported CF task state Some unknown state CF isn't supposed to throw"

        status == null

        1 * client.applicationsV3() >> { applicationsV3 }
        1 * client.tasks() >> { tasks }
        0 * client._

        1 * applicationsV3.list(_) >> {
            Mono.just(ListApplicationsResponse.builder()
                    .resource(ListApplicationsResponse.Resource.builder()
                    .id("app-id")
                    .name("my-cool-app")
                    .build())
                    .build())
        }
        0 * applicationsV3._

        1 * tasks.get(GetTaskRequest.builder().taskId("app-id").build()) >> {
            Mono.just(GetTaskResponse.builder()
                    .id("app-id")
                    .state("Some unknown state CF isn't supposed to throw")
                    .build())
        }
        0 * tasks._
        0 * packages._
        0 * resource._

    }

}