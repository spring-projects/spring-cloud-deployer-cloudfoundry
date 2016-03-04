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
import org.cloudfoundry.client.lib.CloudFoundryException
import org.cloudfoundry.client.lib.CloudFoundryOperations
import org.cloudfoundry.client.lib.domain.CloudApplication
import org.cloudfoundry.client.lib.domain.CloudDomain
import org.cloudfoundry.client.lib.domain.InstanceState
import org.cloudfoundry.client.lib.domain.InstancesInfo
import org.springframework.cloud.deployer.spi.core.AppDefinition
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest
import org.springframework.cloud.deployer.spi.process.DeploymentState
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import spock.lang.Specification
/**
 * @author Greg Turnquist
 */
class CloudFoundryAppDeployerSpec extends Specification {

	CloudFoundryOperations client

	Resource resource

	def setup() {
		client = Mock(CloudFoundryOperations)
		resource = Mock(Resource)
	}

	def "should handle deploying a non-existent app"() {
		given:
		CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties()
		CloudFoundryProcessDeployer deployer = new CloudFoundryProcessDeployer(properties, client)

		def appName = 'my-cool-app'

		when:
		def results = deployer.deploy(new AppDeploymentRequest(
				new AppDefinition(appName, Collections.EMPTY_MAP),
				resource))

		then:
		results == appName

		1 * client.getApplication(appName) >> {
			throw new CloudFoundryException(HttpStatus.BAD_REQUEST, "my-cool-app doesn't exist")
		}
		1 * client.getDefaultDomain() >> { new CloudDomain(null, 'example.com', null) }
		1 * client.createApplication(appName, _, 1024, 1024, [appName + '.example.com'], [])
		1 * client.updateApplicationEnv(appName, [:])
		1 * client.uploadApplication(appName, _, _)
		1 * client.updateApplicationInstances(appName, 1)
		1 * client.startApplication(appName)
		0 * client._

		1 * resource.getInputStream() >> { IOUtils.toInputStream("my app's bits") }
		0 * resource._
	}

	def "should fail when deploying an already-existing app"() {
		given:
		CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties()
		CloudFoundryProcessDeployer deployer = new CloudFoundryProcessDeployer(properties, client)

		def appName = 'my-cool-app'

		when:
		deployer.deploy(new AppDeploymentRequest(
			new AppDefinition(appName, Collections.EMPTY_MAP),
			resource))

		then:
		IllegalStateException e = thrown()
		e.message == "${appName} is already deployed."

		1 * client.getApplication(appName) >> {
			new CloudApplication(null, appName)
		}
		0 * client._

		0 * resource._
	}

	def "should handle passing environmental properties through to cloud foundry"() {
		given:
		CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties()
		CloudFoundryProcessDeployer deployer = new CloudFoundryProcessDeployer(properties, client)

		def appName = 'my-cool-app'

		when:
		def results = deployer.deploy(new AppDeploymentRequest(
				new AppDefinition(appName, Collections.EMPTY_MAP),
				resource,
				['env.PROP1': 'something', 'env.PROP2': 'something else', 'PROP3': "shouldn't have this"]
		))

		then:
		results == appName

		1 * client.getApplication(appName) >> {
			throw new CloudFoundryException(HttpStatus.NOT_FOUND, "Not Found", "Application not found");
		}
		1 * client.getDefaultDomain() >> { new CloudDomain(null, 'example.com', null) }
		1 * client.createApplication(appName, _, 1024, 1024, [appName + '.example.com'], [])
		1 * client.updateApplicationEnv(appName, ['PROP1':'something', 'PROP2':'something else'])
		1 * client.uploadApplication(appName, _, _)
		1 * client.updateApplicationInstances(appName, 1)
		1 * client.startApplication(appName)
		0 * client._

		1 * resource.getInputStream() >> { IOUtils.toInputStream("my app's bits") }
		0 * resource._
	}

	def "should backoff in case uploading an app fails"() {
		given:
		CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties()
		CloudFoundryProcessDeployer deployer = new CloudFoundryProcessDeployer(properties, client)

		def appName = 'my-cool-app'

		when:
		deployer.deploy(new AppDeploymentRequest(
				new AppDefinition(appName, Collections.EMPTY_MAP),
				resource,
				['env.PROP1': 'something', 'env.PROP2': 'something else', 'PROP3': "shouldn't have this"]
		))

		then:
		RuntimeException e = thrown()
		e.message.contains('Exception trying to deploy ' + AppDeploymentRequest.canonicalName)
		e.cause.message == 'Your bits do not exist'

		1 * client.getApplication(appName) >> {
			throw new CloudFoundryException(HttpStatus.NOT_FOUND, "Not Found", "Application not found");
		}
		1 * client.getDefaultDomain() >> { new CloudDomain(null, 'example.com', null) }
		1 * client.createApplication(appName, _, 1024, 1024, [appName + '.example.com'], [])
		1 * client.updateApplicationEnv(appName, ['PROP1':'something', 'PROP2':'something else'])
		0 * client._

		1 * resource.getInputStream() >> { throw new FileNotFoundException('Your bits do not exist') }
		0 * resource._
	}

	def "should handle un-deploying an existing app"() {
		given:
		CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties()
		CloudFoundryProcessDeployer deployer = new CloudFoundryProcessDeployer(properties, client)

		def appName = 'my-cool-app'

		when:
		deployer.undeploy(appName)

		then:

		1 * client.getApplication(appName) >> {
			new CloudApplication(null, appName)
		}
		1 * client.deleteApplication(appName)
		0 * client._

		0 * resource._
	}

	def "should fail to un-deploy a non-existent app"() {
		given:
		CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties()
		CloudFoundryProcessDeployer deployer = new CloudFoundryProcessDeployer(properties, client)

		def appName = 'my-cool-app'

		when:
		deployer.undeploy(appName)

		then:
		IllegalStateException e = thrown()
		e.message == "${appName} is not deployed."

		1 * client.getApplication(appName) >> {
			throw new CloudFoundryException(HttpStatus.BAD_REQUEST, "my-cool-app doesn't exist")
		}
		0 * client._

		0 * resource._
	}

	def "should handle reading the status of a deployed app"() {
		given:
		CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties()
		CloudFoundryProcessDeployer deployer = new CloudFoundryProcessDeployer(properties, client)

		def appName = 'my-cool-app'

		def instance1 = [index: '0', state: InstanceState.RUNNING.toString()]
		def instance2 = [index: '1', state: InstanceState.RUNNING.toString()]

		2 * client.getApplicationInstances(appName) >> {
			new InstancesInfo([instance1, instance2])
		}

		when:
		def status = deployer.status(appName)

		then:
		status.processDeploymentId == appName
		status.state == DeploymentState.deployed

		1 * client.getApplicationInstances(appName) >> {
			new InstancesInfo([instance1, instance2])
		}
		0 * client._

		0 * resource._
	}

	def "should handle reading the status of an app in the middle of getting deployed"() {
		given:
		CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties()
		CloudFoundryProcessDeployer deployer = new CloudFoundryProcessDeployer(properties, client)

		def appName = 'my-cool-app'

		def instance1 = [index: '0', state: InstanceState.STARTING.toString()]
		def instance2 = [index: '1', state: InstanceState.RUNNING.toString()]

		2 * client.getApplicationInstances(appName) >> {
			new InstancesInfo([instance1, instance2])
		}

		when:
		def status = deployer.status(appName)

		then:
		status.processDeploymentId == appName
		status.state == DeploymentState.deploying

		1 * client.getApplicationInstances(appName) >> {
			new InstancesInfo([instance1, instance2])
		}
		0 * client._

		0 * resource._
	}

	def "should handle failing to read a cloud foundry app's status"() {
		given:
		CloudFoundryAppDeployProperties properties = new CloudFoundryAppDeployProperties()
		CloudFoundryProcessDeployer deployer = new CloudFoundryProcessDeployer(properties, client)

		def appName = 'my-cool-app'

		1 * client.getApplicationInstances(appName) >> {
			throw new CloudFoundryException(HttpStatus.GATEWAY_TIMEOUT)
		}

		when:
		def status = deployer.status(appName)

		then:
		status.processDeploymentId == appName
		status.state == DeploymentState.failed

		1 * client.getApplicationInstances(appName) >> {
			new InstancesInfo([[index: '0', state: InstanceState.STARTING.toString()]])
		}
		0 * client._

		0 * resource._
	}

}