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
import org.cloudfoundry.client.lib.CloudFoundryOperations
import org.cloudfoundry.client.lib.domain.InstanceInfo
import org.cloudfoundry.client.lib.domain.InstanceState
import org.cloudfoundry.client.lib.domain.InstancesInfo
import org.springframework.cloud.deployer.spi.app.DeploymentState
import spock.lang.Specification
/**
 * @author Greg Turnquist
 */
class CloudFoundryInstanceSpec extends Specification {

	CloudFoundryOperations client

	def setup() {
		client = Mock(CloudFoundryOperations)
	}

	def "should be able to lookup environment variables from Cloud Foundry"() {
		given:
		def appName = 'my-cool-app'

		def cloudFoundryInstance = new CloudFoundryInstance(appName, new InstanceInfo([index: '0', state: InstanceState.RUNNING.toString()]), client)

		when:
		def attributes = cloudFoundryInstance.attributes

		then:
		attributes == ['COOL_PROPERTY': 'cool value']

		1 * client.getApplicationEnvironment(appName) >> {
			['COOL_PROPERTY': 'cool value']
		}
		0 * client._
	}

	def "should be able to read a flapping app's state from cloud foundry"() {
		given:
		def appName = 'my-cool-app'

		def instance = [index: '0', state: InstanceState.FLAPPING.toString()]

		1 * client.getApplicationInstances(appName) >> {
			new InstancesInfo([instance])
		}

		when:
		def cloudFoundryInstance = new CloudFoundryInstance(appName, new InstanceInfo(instance), client)

		then:
		cloudFoundryInstance.state == DeploymentState.unknown

		0 * client._
	}

	def "should be able to read a crashed app's state from cloud foundry"() {
		given:
		def appName = 'my-cool-app'

		def instance = [index: '0', state: InstanceState.CRASHED.toString()]

		1 * client.getApplicationInstances(appName) >> {
			new InstancesInfo([instance])
		}

		when:
		def cloudFoundryInstance = new CloudFoundryInstance(appName, new InstanceInfo(instance), client)

		then:
		cloudFoundryInstance.state == DeploymentState.failed

		0 * client._
	}

	/**
	 * NOTE: I've yet to see a situation where an app is DOWN and yet produces an InstanceInfo containing this state.
	 */
	def "should be able to read a down app's state from cloud foundry"() {
		given:
		def appName = 'my-cool-app'

		def instance = [index: '0', state: InstanceState.DOWN.toString()]

		1 * client.getApplicationInstances(appName) >> {
			new InstancesInfo([instance])
		}

		when:
		def cloudFoundryInstance = new CloudFoundryInstance(appName, new InstanceInfo(instance), client)

		then:
		cloudFoundryInstance.state == DeploymentState.failed

		0 * client._
	}

	/**
	 * NOTE: I've yet to see a situation where an app is UNKNOWN and yet produces an InstanceInfo containing this state.
	 */
	def "should be able to read an unknown app's state from cloud foundry"() {
		given:
		def appName = 'my-cool-app'

		def instance = [index: '0', state: InstanceState.UNKNOWN.toString()]

		1 * client.getApplicationInstances(appName) >> {
			new InstancesInfo([instance])
		}

		when:
		def cloudFoundryInstance = new CloudFoundryInstance(appName, new InstanceInfo(instance), client)

		then:
		cloudFoundryInstance.state == DeploymentState.unknown

		0 * client._
	}

}
