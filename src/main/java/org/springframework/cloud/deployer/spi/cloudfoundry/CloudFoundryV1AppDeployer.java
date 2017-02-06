package org.springframework.cloud.deployer.spi.cloudfoundry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.cloudfoundry.client.lib.CloudFoundryException;
import org.cloudfoundry.client.lib.CloudFoundryOperations;
import org.cloudfoundry.client.lib.StartingInfo;
import org.cloudfoundry.client.lib.domain.CloudApplication;
import org.cloudfoundry.client.lib.domain.Staging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * Created by mpollack on 2/6/17.
 */
public class CloudFoundryV1AppDeployer extends AbstractCloudFoundryDeployer implements AppDeployer {


	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryV1AppDeployer.class);

	private final AppNameGenerator applicationNameGenerator;

	private final CloudFoundryOperations cloudFoundryOperations;

	public CloudFoundryV1AppDeployer(AppNameGenerator applicationNameGenerator,
								   CloudFoundryDeploymentProperties deploymentProperties,
								   CloudFoundryOperations cloudFoundryOperations) {
		super(deploymentProperties);
		this.cloudFoundryOperations = cloudFoundryOperations;
		this.applicationNameGenerator = applicationNameGenerator;
	}

	@Override
	public String deploy(AppDeploymentRequest request) {
		String appName = deploymentId(request);

		logger.info("deploy: Getting Status for app = {}", appName);
		AppStatus appStatus = status(appName);
		assertApplicationDoesNotExist(appName, appStatus);

		Staging staging = new Staging(null, buildpack(request));

		//Do not know if we need to create the URI.
		String url = appName + "." + cloudFoundryOperations.getDefaultDomain().getName();
		logger.info("deploy: Creating app = {}", appName);
		cloudFoundryOperations.createApplication(appName,
			staging,
			diskQuota(request),
			memory(request),
			Collections.singletonList(url),
			new ArrayList<String>(servicesToBind(request)));
		Map<String, String> environmentVariables = getEnvironmentVariables(appName, request);

		logger.info("deploy: Updating environment variables for app = {}", appName);
		cloudFoundryOperations.updateApplicationEnv(appName, environmentVariables);

		try {
			logger.info("deploy: Uploading app = {}", appName);
			cloudFoundryOperations.uploadApplication(appName, "spring-cloud-deployer-cloudfoundry",
				request.getResource().getInputStream());
		} catch (Exception e) {
			throw new RuntimeException("Exception trying to deploy " + request, e);
		}


		logger.info("deploy: Updating application instances for app {}", appName);
		cloudFoundryOperations.updateApplicationInstances(appName, instances(request));

		// What is missing
		// Domain
		// HealthCheck
		// Host
		// No Route
		// Route path ?  is that the url above?

		startApplication(appName);

		logger.info("deploy: returning after starting app {}", appName);
		return appName;
	}

	@Async
	public void startApplication(String appName) {
		logger.info("deploy: Starting app {}", appName);
		StartingInfo startingInfo = cloudFoundryOperations.startApplication(appName);

		if (startingInfo != null) {
			logger.info("deploy: StartingInfo staging file = {} ", startingInfo.getStagingFile());
		}
	}

	@Override
	public void undeploy(String id) {
		if (appExists(id)) {
			cloudFoundryOperations.deleteApplication(id);
		} else {
			throw new IllegalStateException(id + " is not deployed.");
		}
	}

	@Override
	public AppStatus status(String id) {

		try {
			AppStatus.Builder builder = AppStatus.of(id);
			CloudApplication cloudApplication = cloudFoundryOperations.getApplication(id);
			CloudFoundryV1AppInstanceStatus generalStateStatus = new CloudFoundryV1AppInstanceStatus(cloudApplication);
			//Perform a 'light' AppStatus operation, no instance information, will break runtime operations ATM.
			return builder.generalState(generalStateStatus.getState()).build();
		} catch (CloudFoundryException e) {
			if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
				logger.info("Status not found for app {} ", id);
			}
			return createEmptyAppStatus(id);
		} catch (Exception e) {
			logger.error("Could not get status for app {}.  Defaulting to DeploymentState.error.", e);
			return createErrorAppStatus(id);
		}
//
//		int i = 0;

		// Note that in the v1 client API, InstanceInfo has an index property, whereas here we just 'assign' it an index.

// 		for (InstanceDetail instanceDetail : applicationDetail.getInstanceDetails()) {
//		builder.with(new CloudFoundryAppInstanceStatus(applicationDetail, instanceDetail, i++));
//	}
//		for (; i < applicationDetail.getInstances(); i++) {
//		builder.with(new CloudFoundryAppInstanceStatus(applicationDetail, null, i));
//	}


//		List<InstanceInfo> instanceInfos = instancesInfo.getInstances();

	}


	private void assertApplicationDoesNotExist(String deploymentId, AppStatus status) {
		DeploymentState state = status.getState();
		if (state != DeploymentState.unknown && state != DeploymentState.error) {
			throw new IllegalStateException(String.format("App %s is already deployed with state %s", deploymentId, state));
		}
	}


	private AppStatus createErrorAppStatus(String deploymentId) {
		return AppStatus.of(deploymentId)
			.generalState(DeploymentState.error)
			.build();
	}

	private AppStatus createEmptyAppStatus(String deploymentId) {
		return AppStatus.of(deploymentId)
			.build();
	}

	private boolean appExists(String appName) {

		try {
			cloudFoundryOperations.getApplication(appName);
			return true;
		} catch (HttpStatusCodeException e) {
			return false;
		}
	}

	public String deploymentId(AppDeploymentRequest request) {
		String prefix = Optional.ofNullable(request.getDeploymentProperties().get(AppDeployer.GROUP_PROPERTY_KEY))
			.map(group -> String.format("%s-", group))
			.orElse("");

		String appName = String.format("%s%s", prefix, request.getDefinition().getName());

		return this.applicationNameGenerator.generateAppName(appName);
	}


}
