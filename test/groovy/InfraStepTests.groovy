import org.junit.Before
import org.junit.Ignore
import org.junit.Test

import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertEquals

class InfraStepTests extends BaseTest {
  static final String scriptName = "vars/infra.groovy"
  static final String defaultArtifactCachingProxyProvider = 'azure'
  static final String anotherArtifactCachingProxyProvider = 'do'
  static final String invalidArtifactCachingProxyProvider = 'foo'
  static final String artifactCachingProxyProvidersWithoutAnotherProvider = 'aws,azure'
  static final String healthCheckScriptSh = 'curl --fail --silent --show-error --location $HEALTHCHECK'
  static final String healthCheckScriptBat = 'curl --fail --silent --show-error --location %HEALTHCHECK%'
  static final String changeUrl = 'https://github.com/jenkins-infra/pipeline-library/pull/123'
  static final String prLabelsContainSkipACPScriptSh = 'curl --silent -H "Accept: application/vnd.github+json" -H "Authorization: Bearer $GH_TOKEN" https://api.github.com/repos/jenkins-infra/pipeline-library/issues/123/labels | grep --ignore-case "skip-artifact-caching-proxy"'
  static final String prLabelsContainSkipACPScriptBat = 'curl --silent -H "Accept: application/vnd.github+json" -H "Authorization: Bearer %GH_TOKEN%" https://api.github.com/repos/jenkins-infra/pipeline-library/issues/123/labels | findstr /i "skip-artifact-caching-proxy"'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
    helper.registerAllowedMethod('getBuildCredentialsId', [], { 'aCredentialsId' })
  }

  @Test
  void testIsRunningOnJenkinsInfra() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://ci.jenkins.io/'
    assertTrue(script.isRunningOnJenkinsInfra())
  }

  @Test
  void testIsTrusted() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://trusted.ci.jenkins.io:1443/'
    binding.setVariable('env', env)
    assertTrue(script.isTrusted())
  }

  @Test
  void testWithDockerCredentials() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://ci.jenkins.io/'
    def isOK = false
    script.withDockerCredentials() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    assertJobStatusSuccess()
  }

  @Test
  void testWithDockerCredentialsOutsideInfra() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://foo/'
    def isOK = false
    script.withDockerCredentials() {
      isOK = true
    }
    printCallStack()
    assertFalse(isOK)
    assertTrue(assertMethodCallContainsPattern('echo', 'Cannot use Docker credentials outside of jenkins infra environment'))
    assertJobStatusSuccess()
  }

  @Test
  void testWithDockerPushCredentials() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://ci.jenkins.io/'
    def isOK = false
    script.withDockerPushCredentials() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    assertJobStatusSuccess()
  }

  @Test
  void testWithDockerPushCredentialsOutsideInfra() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://foo/'
    def isOK = false
    script.withDockerPushCredentials() {
      isOK = true
    }
    printCallStack()
    assertFalse(isOK)
    assertTrue(assertMethodCallContainsPattern('echo', 'Cannot use Docker credentials outside of jenkins infra environments'))
    assertJobStatusSuccess()
  }

  @Test
  void testWithDockerPullCredentials() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://ci.jenkins.io/'
    def isOK = false
    script.withDockerPullCredentials() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('sh', 'echo "${DOCKER_CONFIG_PSW}" | "${CONTAINER_BIN}" login --username "${DOCKER_CONFIG_USR}" --password-stdin'))
  }

  @Test
  void testWithDockerPullCredentialsWindows() throws Exception {
    helper.registerAllowedMethod('isUnix', [], { false })
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://ci.jenkins.io/'
    def isOK = false
    script.withDockerPullCredentials() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('powershell', 'Invoke-Expression "${Env:CONTAINER_BIN} login --username ${Env:DOCKER_CONFIG_USR} --password ${Env:DOCKER_CONFIG_PSW}"'))
  }

  @Test
  void testWithDockerPullCredentialsOutsideInfra() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://foo/'
    def isOK = false
    script.withDockerPullCredentials() {
      isOK = true
    }
    printCallStack()
    assertFalse(isOK)
    assertTrue(assertMethodCallContainsPattern('echo', 'Cannot use Docker credentials outside of jenkins infra environments'))
    assertJobStatusSuccess()
  }

  @Test
  void testCheckoutWithEnvVariable() throws Exception {
    def script = loadScript(scriptName)
    env.BRANCH_NAME = 'BRANCH'
    script.checkoutSCM()
    printCallStack()
    assertJobStatusSuccess()
  }

  @Test
  void testCheckoutWithArgument() throws Exception {
    def script = loadScript(scriptName)
    script.checkoutSCM('foo.git')
    printCallStack()
    assertJobStatusSuccess()
  }

  @Test
  void testCheckoutWithoutArgument() throws Exception {
    def script = loadScript(scriptName)
    try {
      script.checkoutSCM()
    } catch(e) {
      //NOOP
    }
    printCallStack()
    assertTrue(assertMethodCallContainsPattern('error', 'buildPlugin must be used as part of a Multibranch Pipeline *or* a `repo` argument must be provided'))
    assertJobStatusFailure()
  }

  @Test
  void testWithArtifactCachingProxy() throws Exception {
    def script = loadScript(scriptName)
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then it notices the use of the default artifact caching provider
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: using artifact caching proxy from '${defaultArtifactCachingProxyProvider}' provider."))
    // then configFile contains the default artifact caching proxy provider id
    assertTrue(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${defaultArtifactCachingProxyProvider}"))
    // then configFileProvider is correctly set
    assertTrue(assertMethodCallContainsPattern('configFileProvider', '[OK]'))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxyNoProvidersEnvVar() throws Exception {
    def script = loadScript(scriptName)
    // when running with an available provider different from the default requested one
    // without ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS env var declared
    env.ARTIFACT_CACHING_PROXY_PROVIDER = anotherArtifactCachingProxyProvider
    env.ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS = null
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then it notices the use of the requested artifact caching provider
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: using artifact caching proxy from '${anotherArtifactCachingProxyProvider}' provider."))
    // then there is a call to configFile containing the requested artifact caching proxy provider id
    assertTrue(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${anotherArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxyEmptyProvidersEnvVar() throws Exception {
    def script = loadScript(scriptName)
    // when running with an available provider different from the default requested one
    env.ARTIFACT_CACHING_PROXY_PROVIDER = anotherArtifactCachingProxyProvider
    env.ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS = ''
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then it notices the use of the requested artifact caching provider
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: using artifact caching proxy from '${anotherArtifactCachingProxyProvider}' provider."))
    // then there is a call to configFile containing the requested artifact caching proxy provider id
    assertTrue(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${anotherArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxyInvalidProvidersEnvVar() throws Exception {
    def script = loadScript(scriptName)
    // when running with an available provider different from the default requested one
    // with an invalid provider configured with ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS env var declared
    env.ARTIFACT_CACHING_PROXY_PROVIDER = anotherArtifactCachingProxyProvider
    env.ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS = invalidArtifactCachingProxyProvider
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then it notices invalid or unavailable artifact caching provider has been requested and that it will fallback to repo.jenkins-ci.org
    assertTrue(assertMethodCallContainsPattern('echo', "WARNING: invalid or unavailable artifact caching proxy provider '${anotherArtifactCachingProxyProvider}' requested by the agent, will use repo.jenkins-ci.org"))
    // then there is no call to configFile containing the requested artifact caching proxy provider id
    assertFalse(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${anotherArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxyEmptyRequestedProvider() throws Exception {
    def script = loadScript(scriptName)
    // when running with an empty artifact caching proxy provider requested
    env.ARTIFACT_CACHING_PROXY_PROVIDER = ''
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then an healthcheck is performed on the provider
    assertTrue(assertMethodCallContainsPattern('sh', healthCheckScriptSh) || assertMethodCallContainsPattern('bat', healthCheckScriptBat))
    // then it notices the use of the default artifact caching provider
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: using artifact caching proxy from '${defaultArtifactCachingProxyProvider}' provider."))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxyInvalidRequestedProvider() throws Exception {
    def script = loadScript(scriptName)
    // when running with an invalid artifact caching proxy provider requested
    env.ARTIFACT_CACHING_PROXY_PROVIDER = invalidArtifactCachingProxyProvider
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then it notices invalid or unavailable artifact caching provider has been requested and that it will fallback to repo.jenkins-ci.org
    assertTrue(assertMethodCallContainsPattern('echo', "WARNING: invalid or unavailable artifact caching proxy provider '${invalidArtifactCachingProxyProvider}' requested by the agent, will use repo.jenkins-ci.org"))
    // then there is no call to configFile containing the requested artifact caching proxy provider id
    assertFalse(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${invalidArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }
  @Test
  void testWithArtifactCachingProxyUnavailableRequestedProvider() throws Exception {
    def script = loadScript(scriptName)
    // when running with an unavailable requested provider
    env.ARTIFACT_CACHING_PROXY_PROVIDER = anotherArtifactCachingProxyProvider
    env.ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS = artifactCachingProxyProvidersWithoutAnotherProvider
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then it notices invalid or unavailable artifact caching provider has been requested and that it will fallback to repo.jenkins-ci.org
    assertTrue(assertMethodCallContainsPattern('echo', "WARNING: invalid or unavailable artifact caching proxy provider '${anotherArtifactCachingProxyProvider}' requested by the agent, will use repo.jenkins-ci.org"))
    // then there is no call to configFile containing the requested artifact caching proxy provider id
    assertFalse(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${anotherArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxyWithoutSkipArtifactCachingProxyOnPullRequest() throws Exception {
    def script = loadScript(scriptName)

    // when running on a pull request without a "skip-artifact-caching-proxy" label
    env.CHANGE_URL = changeUrl
    // Mock the absence of "skip-artifact-caching-proxy" label
    helper.addShMock(prLabelsContainSkipACPScriptSh, '', 1)
    helper.addBatMock(prLabelsContainSkipACPScriptBat, '', 1)
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then a check is performed on the pull request labels
    assertTrue(assertMethodCallContainsPattern('sh', prLabelsContainSkipACPScriptSh) || assertMethodCallContainsPattern('bat', prLabelsContainSkipACPScriptBat))
    // then it doesn't notice the skipping of artifact-caching-proxy
    assertFalse(assertMethodCallContainsPattern('echo', "INFO: the label 'skip-artifact-caching-proxy' has been applied to the pull request, will use repo.jenkins-ci.org"))
    // then there is a call to configFile containing the default artifact caching proxy provider id
    assertTrue(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${defaultArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxySkipArtifactCachingProxyOnPullRequest() throws Exception {
    def script = loadScript(scriptName)

    // when running on a pull request with a "skip-artifact-caching-proxy" label
    env.CHANGE_URL = changeUrl
    // Mock a "skip-artifact-caching-proxy" label
    helper.addShMock(prLabelsContainSkipACPScriptSh, '', 0)
    helper.addBatMock(prLabelsContainSkipACPScriptBat, '', 0)
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then a check is performed on the pull request labels
    assertTrue(assertMethodCallContainsPattern('sh', prLabelsContainSkipACPScriptSh) || assertMethodCallContainsPattern('bat', prLabelsContainSkipACPScriptBat))
    // then it notices the skipping of artifact-caching-proxy
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: the label 'skip-artifact-caching-proxy' has been applied to the pull request, will use repo.jenkins-ci.org"))
    // then there is no call to configFile containing the default artifact caching proxy provider id
    assertFalse(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${defaultArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxySkipArtifactCachingProxyOnBranchWithoutPullRequest() throws Exception {
    def script = loadScript(scriptName)

    // when running on a branch which doesn't have a pull request associated
    env.CHANGE_URL = null
    // Mock a "skip-artifact-caching-proxy" label
    helper.addShMock(prLabelsContainSkipACPScriptSh, '', 0)
    helper.addBatMock(prLabelsContainSkipACPScriptBat, '', 0)
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then a check is not performed on the labels
    assertFalse(assertMethodCallContainsPattern('sh', prLabelsContainSkipACPScriptSh) || assertMethodCallContainsPattern('bat', prLabelsContainSkipACPScriptBat))
    // then it doesn't notice the skipping of artifact-caching-proxy
    assertFalse(assertMethodCallContainsPattern('echo', "INFO: the label 'skip-artifact-caching-proxy' has been applied to the pull request, will use repo.jenkins-ci.org"))
    // then there is a call to configFile containing the default artifact caching proxy provider id
    assertTrue(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${defaultArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxyReachableRequestedProvider() throws Exception {
    def script = loadScript(scriptName)
    // when running with a reachable requested provider
    env.ARTIFACT_CACHING_PROXY_PROVIDER = anotherArtifactCachingProxyProvider
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then an healthcheck is performed on the provider
    assertTrue(assertMethodCallContainsPattern('sh', healthCheckScriptSh) || assertMethodCallContainsPattern('bat', healthCheckScriptBat))
    // then it does not notice the provider isn't reachable and that it will fallback to repo.jenkins-ci.org
    assertFalse(assertMethodCallContainsPattern('echo', "WARNING: the artifact caching proxy from '${anotherArtifactCachingProxyProvider}' provider isn't reachable, will use repo.jenkins-ci.org"))
    // then there is a call to configFile containing the requested artifact caching proxy provider id
    assertTrue(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${anotherArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxyUnreachableRequestedProvider() throws Exception {
    def script = loadScript(scriptName)
    // Mock an healthcheck fail
    helper.addShMock(healthCheckScriptSh, '', 1)
    helper.addBatMock(healthCheckScriptBat, '', 1)

    // when running with an unreachable requested provider
    env.ARTIFACT_CACHING_PROXY_PROVIDER = anotherArtifactCachingProxyProvider
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then an healthcheck is performed on the provider
    assertTrue(assertMethodCallContainsPattern('sh', healthCheckScriptSh) || assertMethodCallContainsPattern('bat', healthCheckScriptBat))
    // then it notices the provider isn't reachable and that it will fallback to repo.jenkins-ci.org
    assertTrue(assertMethodCallContainsPattern('echo', "WARNING: the artifact caching proxy from '${anotherArtifactCachingProxyProvider}' provider isn't reachable, will use repo.jenkins-ci.org"))
    // then there is no call to configFile containing the requested artifact caching proxy provider id
    assertFalse(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${anotherArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testRunMavenWithArtifactCachingProxy() throws Exception {
    def script = loadScript(scriptName)
    // when running with useArtifactCachingProxy set to true
    script.runMaven(['clean verify'], 11, null, null, null, true)
    printCallStack()
    // then it notices the use of the default artifact caching provider
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: using artifact caching proxy from '${defaultArtifactCachingProxyProvider}' provider."))
    // then configFile contains the default artifact caching proxy provider id
    assertTrue(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${defaultArtifactCachingProxyProvider}"))
    // then configFileProvider is correctly set
    assertTrue(assertMethodCallContainsPattern('configFileProvider', '[OK]'))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testRunMavenWithArtifactCachingProxySkipArtifactCachingProxyOnPullRequest() throws Exception {
    def script = loadScript(scriptName)
    // when running on a pull request with a "skip-artifact-caching-proxy" label
    env.CHANGE_URL = changeUrl
    // Mock a "skip-artifact-caching-proxy" label
    helper.addShMock(prLabelsContainSkipACPScriptSh, '', 0)
    helper.addBatMock(prLabelsContainSkipACPScriptBat, '', 0)
    // when running with useArtifactCachingProxy set to true
    script.runMaven(['clean verify'], 11, null, null, null, true)
    printCallStack()
    // then a check is performed on the pull request labels
    assertTrue(assertMethodCallContainsPattern('sh', prLabelsContainSkipACPScriptSh) || assertMethodCallContainsPattern('bat', prLabelsContainSkipACPScriptBat))
    // then it notices the skipping of artifact-caching-proxy
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: the label 'skip-artifact-caching-proxy' has been applied to the pull request, will use repo.jenkins-ci.org"))
    // then there is no call to configFile containing the default artifact caching proxy provider id
    assertFalse(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${defaultArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testRunMavenWithArtifactCachingProxyUnreachableRequestedProvider() throws Exception {
    def script = loadScript(scriptName)
    // Mock an healthcheck fail
    helper.addShMock(healthCheckScriptSh, '', 1)
    helper.addBatMock(healthCheckScriptBat, '', 1)

    // when running with an unreachable requested provider
    env.ARTIFACT_CACHING_PROXY_PROVIDER = anotherArtifactCachingProxyProvider
    // when running with useArtifactCachingProxy set to true
    script.runMaven(['clean verify'], 11, null, null, null, true)
    printCallStack()
    // then an healthcheck is performed on the provider
    assertTrue(assertMethodCallContainsPattern('sh', healthCheckScriptSh) || assertMethodCallContainsPattern('bat', healthCheckScriptBat))
    // then it notices the provider isn't reachable and that it will fallback to repo.jenkins-ci.org
    assertTrue(assertMethodCallContainsPattern('echo', "WARNING: the artifact caching proxy from '${anotherArtifactCachingProxyProvider}' provider isn't reachable, will use repo.jenkins-ci.org"))
    // then there is no call to configFile containing the requested artifact caching proxy provider id
    assertFalse(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${anotherArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }
}
