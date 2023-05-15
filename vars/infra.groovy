#!/usr/bin/env groovy

import io.jenkins.infra.InfraConfig
import jenkins.scm.api.SCMSource
import com.cloudbees.groovy.cps.NonCPS

// Method kept for backward compatibility (as the method is available on the InfraConfig stateful object)
Boolean isRunningOnJenkinsInfra() {
  return new InfraConfig(env).isRunningOnJenkinsInfra()
}

// Method kept for backward compatibility (as the method is available on the InfraConfig stateful object)
Boolean isTrusted() {
  return new InfraConfig(env).isTrusted()
}

// Method kept for backward compatibility (as the method is available on the InfraConfig stateful object)
Boolean isRelease() {
  return new InfraConfig(env).isRelease()
}

// Method kept for backward compatibility (as the method is available on the InfraConfig stateful object)
Boolean isInfra() {
  return new InfraConfig(env).isInfra()
}

//withDockerCredentials deprecated method present for backward compatibility
Object withDockerCredentials(Closure body) {
  return withDockerPushCredentials(body)
}

Object withDockerCredentials(Map orgAndCredentialsId, Closure body) {
  if (orgAndCredentialsId.error) {
    echo orgAndCredentialsId.msg
  } else {
    env.DOCKERHUB_ORGANISATION = orgAndCredentialsId.organisation
    withEnv(["CONTAINER_BIN=${env.CONTAINER_BIN ?: 'docker'}"]){
      withCredentials([
        usernamePassword(credentialsId: orgAndCredentialsId.credentialId, passwordVariable: 'DOCKER_CONFIG_PSW', usernameVariable: 'DOCKER_CONFIG_USR')
      ]) {
        // Logging in on the Dockerhub helps to avoid request limit from DockerHub
        if (isUnix()) {
          sh 'echo "${DOCKER_CONFIG_PSW}" | "${CONTAINER_BIN}" login --username "${DOCKER_CONFIG_USR}" --password-stdin'
        } else {
          powershell 'Invoke-Expression "${Env:CONTAINER_BIN} login --username ${Env:DOCKER_CONFIG_USR} --password ${Env:DOCKER_CONFIG_PSW}"'
        }

        body.call()
        // Logging out to ensure credentials are cleaned up if the current agent is reused
        if (isUnix()) {
          sh '"${CONTAINER_BIN}" logout'
        } else {
          powershell 'Invoke-Expression "${Env:CONTAINER_BIN} logout"'
        }
        return
      } // withCredentials
    }// withEnv
  }
}

Object withDockerPushCredentials(Closure body) {
  orgAndCredentialsId = new InfraConfig(env).getDockerPushOrgAndCredentialsId()
  return withDockerCredentials(orgAndCredentialsId, body)
}

Object withDockerPullCredentials(Closure body) {
  orgAndCredentialsId = new InfraConfig(env).getDockerPullOrgAndCredentialsId()
  return withDockerCredentials(orgAndCredentialsId, body)
}

Object checkoutSCM(String repo = null) {
  // Enable long paths to avoid problems with tests on Windows agents
  if (!isUnix()) {
    bat 'git config --global core.longpaths true'
  }

  if (env.BRANCH_NAME) {
    checkout scm
  } else if (!env.BRANCH_NAME && repo) {
    git repo
  } else {
    error 'buildPlugin must be used as part of a Multibranch Pipeline *or* a `repo` argument must be provided'
  }
}

/**
 * Execute the body passed as closure with a Maven settings file using the
 * Artifact Caching Proxy provider corresponding to the requested one defined
 * via the agent's env.ARTIFACT_CACHING_PROXY variable, or 'azure' if not defined.
 * This allows decreasing JFrog Artifactory bandwidth consumption, and increase reliability.
 * There are currently three providers, one for each cloud used in Jenkins Infrastructure:
 * "aws", "azure" and "do" (DigitalOcean).
 * The available providers can be restricted by setting a global ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS
 * variable on the Jenkins controller, with providers separated by a comma. Ex: 'aws,do' if the Azure provider is unavailable.
 * A 'skip-artifact-caching-proxy' label can be added to pull request in order to punctually disable it.
 * @param useArtifactCachingProxy (default: true) if possible, use an artifact caching proxy in front of repo.jenkins-ci.org to decrease JFrog Artifactory bandwidth usage and to increase reliability
 */
Object withArtifactCachingProxy(boolean useArtifactCachingProxy = true, Closure body) {
  // As the env var ARTIFACT_CACHING_PROXY_PROVIDER can't be set on Azure VM agents,
  // we're specifying a default provider if none is specified.
  final String requestedProxyProvider = env.ARTIFACT_CACHING_PROXY_PROVIDER ?: 'azure'
  final String[] validProxyProviders = ['aws', 'azure', 'do']
  // Useful when a provider is in maintenance (or similar cases), add a global env var in Jenkins controller settings to restrict them.
  // To completely disable the artifact caching proxies, this value can be set to a value absent of validProxyProviders like "none" for example.
  final String configuredAvailableProxyProviders = env.ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS ?: validProxyProviders.join(',')
  final String[] availableProxyProviders = configuredAvailableProxyProviders.split(',')

  // Check if the requested provider is invalid or unavailable
  if (useArtifactCachingProxy && (!validProxyProviders.contains(requestedProxyProvider) || !availableProxyProviders.contains(requestedProxyProvider))) {
    echo "WARNING: invalid or unavailable artifact caching proxy provider '${requestedProxyProvider}' requested by the agent, will use repo.jenkins-ci.org"
    useArtifactCachingProxy = false
  }

  // If the build concerns a pull request, check if there is "skip-artifact-caching-proxy" label applied in case the user doesn't want ACP
  if (useArtifactCachingProxy && env.CHANGE_URL && pullRequest != null) {
    final String skipACPLabel = 'skip-artifact-caching-proxy'
    // Note: the pullRequest object is provided by https://github.com/jenkinsci/pipeline-github-plugin
    if (pullRequest.labels.contains(skipACPLabel)) {
      echo "INFO: the label '$skipACPLabel' has been applied to the pull request, will use repo.jenkins-ci.org"
      useArtifactCachingProxy = false
    }
  }

  // Check if the artifact caching proxy provider is unreachable
  if (useArtifactCachingProxy) {
    boolean healthCheckFailed = false
    withEnv(["HEALTHCHECK=https://repo.${requestedProxyProvider}.jenkins.io/health"]) {
      if (isUnix()) {
        healthCheckFailed = sh(script: 'curl --fail --silent --show-error --location $HEALTHCHECK', returnStatus: true) != 0
      } else {
        healthCheckFailed = bat(script: 'curl --fail --silent --show-error --location %HEALTHCHECK%', returnStatus: true) != 0
      }
    }
    if (healthCheckFailed) {
      echo "WARNING: the artifact caching proxy from '${requestedProxyProvider}' provider isn't reachable, will use repo.jenkins-ci.org"
      useArtifactCachingProxy = false
    }
  }

  // Use the Maven settings with artifact caching proxy config and private auth if everything is still OK
  if (useArtifactCachingProxy) {
    echo "INFO: using artifact caching proxy from '${requestedProxyProvider}' provider."
    configFileProvider(
        [configFile(fileId: "artifact-caching-proxy-${requestedProxyProvider}", variable: 'MAVEN_SETTINGS')]) {
          withEnv(["MAVEN_ARGS=-s $env.MAVEN_SETTINGS"]) {
            body()
          }
        }
  } else {
    body()
  }
}

/**
 * Runs Maven for the specified options in the current workspace.
 * Maven settings will be added by default if needed.
 * @param jdk JDK to be used
 * @param options Options to be passed to the Maven command
 * @param extraEnv Extra environment variables to be passed when invoking the command
 * @param useArtifactCachingProxy (default: true) if possible, use an artifact caching proxy in front of repo.jenkins-ci.org to decrease JFrog Artifactory bandwidth usage and to increase reliability
 * @see withArtifactCachingProxy
 */
Object runMaven(List<String> options, String jdk = '8', List<String> extraEnv = null, Boolean addToolEnv = true, Boolean useArtifactCachingProxy = true) {
  List<String> mvnOptions = ['--batch-mode', '--show-version', '--errors', '--no-transfer-progress']
  withArtifactCachingProxy(useArtifactCachingProxy) {
    mvnOptions.addAll(options)
    mvnOptions.unique()
    String command = "mvn ${mvnOptions.join(' ')}"
    runWithMaven(command, jdk, extraEnv, addToolEnv)
  }
}

/**
 * Runs Maven for the specified options in the current workspace.
 * Maven settings will be added by default if needed.
 * @param Major version of JDK to be used (integer)
 * @param options Options to be passed to the Maven command
 * @param extraEnv Extra environment variables to be passed when invoking the command
 * @param settingsFile specific Maven settings.xml filepath, not taken in account if useArtifactCachingProxy is true
 * @param useArtifactCachingProxy (default: true) if possible, use an artifact caching proxy in front of repo.jenkins-ci.org to decrease JFrog Artifactory bandwidth usage and to increase reliability
 * @see withArtifactCachingProxy
 */
Object runMaven(List<String> options, Integer jdk, List<String> extraEnv = null, Boolean addToolEnv = true, Boolean useArtifactCachingProxy = true) {
  runMaven(options, jdk.toString(), extraEnv, addToolEnv, useArtifactCachingProxy)
}

/**
 * Runs the command with Java and Maven environments.
 * The command may be either Batch or Shell depending on the OS.
 * @param command Command to be executed
 * @param jdk JDK version to be used
 * @param extraEnv Extra environment variables to be passed
 */
Object runWithMaven(String command, String jdk = '8', List<String> extraEnv = null, Boolean addToolEnv = true) {
  List<String> javaEnv = []
  if (addToolEnv) {
    javaEnv += "PATH+MAVEN=${tool 'mvn'}/bin"
  }

  if (extraEnv) {
    javaEnv.addAll(extraEnv)
  }

  if (!javaEnv.any { it.startsWith('MAVEN_OPTS=') }) {
    javaEnv += 'MAVEN_OPTS=-XX:+PrintCommandLineFlags'
  }

  runWithJava(command, jdk, javaEnv, addToolEnv)
}

/**
 * Runs the command with Java environment.
 * {@code PATH} and {@code JAVA_HOME} will be set.
 * The command may be either Batch or Shell depending on the OS.
 * @param command Command to be executed
 * @param jdk JDK version to be used
 * @param extraEnv Extra environment variables to be passed
 */
Object runWithJava(String command, String jdk = '8', List<String> extraEnv = null, Boolean addToolEnv = true) {
  List<String> javaEnv = []
  if (addToolEnv) {
    // Collection of well-known JDK locations on our agent templates (VMs and containers)
    def agentJavaHomes = [
      'linux': [
        // Adoptium (Eclipse Temurin) for Linux - https://github.com/adoptium/containers
        '/opt/java/openjdk',
        // Our own custom VMs/containers - https://github.com/jenkins-infra/packer-images
        "/opt/jdk-${jdk}",
      ],
      'windows': [
        // Adoptium (Eclipse Temurin) for Windows - https://github.com/adoptium/containers
        "C:/openjdk-${jdk}",
        // Our own custom VMs/containers - https://github.com/jenkins-infra/packer-images
        "C:/tools/jdk-${jdk}",
      ],
    ]

    // Prepare the list of JDK locations to search for on the agent
    List<String> javaHomesToTry = agentJavaHomes[isUnix() ? 'linux' : 'windows']

    // Define the java home based on the found JDK (or fallback to the Jenkins tool)
    String javaHome
    for (javaHomeToTry in javaHomesToTry) {
      String javaBinToTry = "${javaHomeToTry}/bin/java"
      if (!isUnix()) {
        javaBinToTry += '.exe' // On windows, binaries have an extension
      }
      if (fileExists(javaBinToTry)) {
        javaHome = javaHomeToTry
        break
      }
    }
    if (!javaHome) {
      String jdkTool = "jdk${jdk}"
      echo "WARNING: switching to the Jenkins tool named ${jdkTool} to set the environment variables JAVA_HOME and PATH, because no java installation found in any of the following locations: ${javaHomesToTry.join(", ")}"
      javaHome = tool jdkTool
    }

    // Define the environment to ensure that the correct JDK is used
    javaEnv += "JAVA_HOME=${javaHome}"
    javaEnv += 'PATH+JAVA=${JAVA_HOME}/bin'

    echo "INFO: Using JAVA_HOME=${javaHome} as default JDK home."
  }

  if (extraEnv) {
    javaEnv.addAll(extraEnv)
  }

  withEnv(javaEnv) {
    if (isUnix()) {
      // TODO JENKINS-44231 candidate for simplification
      sh command
    } else {
      bat command
    }
  }
}

String gradleCommand(List<String> gradleOptions) {
  String command = "gradlew ${gradleOptions.join(' ')}"
  if (isUnix()) {
    command = "./" + command
  }
  return command
}

/**
 * Make sure the code block is run in a node with the all the specified nodeLabels as labels, if already running in that
 * it simply executes the code block, if not allocates the desired node and runs the code inside it
 * Node labels must be specified as String formed by a comma separated list of labels
 * Please note that this step is not able to manage complex labels and checks for them literally, so do not try to use labels like 'docker,(lowmemory&&linux)' as it will result in
 * the step launching a new node as is unable to find the label '(lowmemory&amp;&amp;linux)' in the list of labels for the current node
 *
 * @param nodeLabels The node labels, a string containing the comma separated labels
 * @param body The code to run in the desired node
 */
void ensureInNode(nodeLabels, body) {
  def inCorrectNode = true
  def splitted = nodeLabels.split(',')
  if (!env.NODE_LABELS) {
    inCorrectNode = false
  } else {
    for (label in splitted) {
      if (!env.NODE_LABELS.contains(label)) {
        inCorrectNode = false
        break
      }
    }
  }

  if (inCorrectNode) {
    body()
  } else {
    node(splitted.join('&&')) {
      body()
    }
  }
}

/**
 * Record artifacts created by this build which could be published via Incrementals (JEP-305).
 * Call at most once per build, on a Linux node, after running mvn -Dset.changelist install.
 * Follow up with #maybePublishIncrementals.
 */
void prepareToPublishIncrementals() {
  // MINSTALL-126 would make this easier by letting us publish to a different directory to begin with:
  String m2repo = sh script: 'mvn -Dset.changelist -Dexpression=settings.localRepository -q -DforceStdout help:evaluate', returnStdout: true
  // No easy way to load both of these in one command: https://stackoverflow.com/q/23521889/12916
  String version = sh script: 'mvn -Dset.changelist -Dexpression=project.version -q -DforceStdout help:evaluate', returnStdout: true
  echo "Collecting $version from $m2repo for possible Incrementals publishing"
  dir(m2repo) {
    fingerprint '**/*-rc*.*/*-rc*.*' // includes any incrementals consumed
    archiveArtifacts "**/$version/*$version*"
  }
}

/**
 * When appropriate, publish artifacts from the current build to the Incrementals repository.
 * Call at the end of the build, outside any node, when #prepareToPublishIncrementals may have been called previously.
 * See INFRA-1571 and JEP-305.
 */
void maybePublishIncrementals() {
  if (new InfraConfig(env).isRunningOnJenkinsInfra() && currentBuild.currentResult == 'SUCCESS') {
    stage('Deploy') {
      withCredentials([string(credentialsId: 'incrementals-publisher-token', variable: 'FUNCTION_TOKEN')]) {
        httpRequest url: 'https://incrementals.jenkins.io/',
        httpMode: 'POST',
        contentType: 'APPLICATION_JSON',
        validResponseCodes: '100:599',
        timeout: 300,
        requestBody: /{"build_url":"$BUILD_URL"}/,
        customHeaders: [[name: 'Authorization', value: 'Bearer ' + FUNCTION_TOKEN]],
        consoleLogResponseBody: true
      }
    }
  } else {
    echo 'Skipping deployment to Incrementals'
  }
}

void publishDeprecationCheck(String deprecationSummary, String deprecationMessage) {
  echo "WARNING: ${deprecationMessage}"
  publishChecks name: 'pipeline-library', summary: deprecationSummary, conclusion: 'NEUTRAL', text: deprecationMessage
}
