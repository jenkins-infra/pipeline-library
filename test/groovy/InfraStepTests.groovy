import org.junit.Before
import org.junit.Ignore
import org.junit.Test

import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertEquals

import mock.PullRequest

class InfraStepTests extends BaseTest {
  static final String scriptName = "vars/infra.groovy"
  static final String defaultArtifactCachingProxyProvider = 'azure'
  static final String anotherArtifactCachingProxyProvider = 'do'
  static final String invalidArtifactCachingProxyProvider = 'foo'
  static final String artifactCachingProxyProvidersWithoutAnotherProvider = 'aws,azure'
  static final String healthCheckScriptSh = 'curl --fail --silent --show-error --location $HEALTHCHECK'
  static final String healthCheckScriptBat = 'curl --fail --silent --show-error --location %HEALTHCHECK%'
  static final String changeUrl = 'https://github.com/jenkins-infra/pipeline-library/pull/123'
  static final String defaultServicePrincipalCredentialsId = 'a-service-principal-writer'
  static final String defaultFileShare = 'a-file-share'
  static final String defaultFileShareStorageAccount = 'astorageaccount'
  static final String defaultTokenDuration = '10'
  static final String defaultTokenPermissions = 'dlrw'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    // Mock Pipeline methods which are not already declared in the parent class
    helper.registerAllowedMethod('azureServicePrincipal', [Map.class], { m -> m })
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
  void testIsRelease() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://release.ci.jenkins.io/'
    binding.setVariable('env', env)
    assertTrue(script.isRelease())
  }

  @Test
  void testIsInfra() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://infra.ci.jenkins.io/'
    binding.setVariable('env', env)
    assertTrue(script.isInfra())
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
    assertTrue(assertMethodCallContainsPattern('pwsh', 'Write-Output ${env:DOCKER_CONFIG_PSW} | & ${Env:CONTAINER_BIN} login --username ${Env:DOCKER_CONFIG_USR} --password-stdin'))
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
    // When running withArtifactCachingProxy with its default value
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
  void testWithArtifactCachingProxyFalseAsParameter() throws Exception {
    def script = loadScript(scriptName)
    // When running withArtifactCachingProxy with false passed as parameter
    def isOK = false
    script.withArtifactCachingProxy(false) {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then it doesn't notice the use of the default artifact caching provider
    assertFalse(assertMethodCallContainsPattern('echo', "INFO: using artifact caching proxy from '${defaultArtifactCachingProxyProvider}' provider."))
    // then there is no call to the configFile containing the default artifact caching proxy provider id
    assertFalse(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${defaultArtifactCachingProxyProvider}"))
    // then there is no call to the configFileProvider correctly set
    assertFalse(assertMethodCallContainsPattern('configFileProvider', '[OK]'))
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
    // Mock a pull request label different than "skip-artifact-caching-proxy"
    binding.setProperty('pullRequest', new PullRequest(['a-label']))
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
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
    // Mock a "skip-artifact-caching-proxy" pull request label
    binding.setProperty('pullRequest', new PullRequest(['a-label', 'skip-artifact-caching-proxy']))
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
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
    // Mock a "skip-artifact-caching-proxy" pull request label
    binding.setProperty('pullRequest', new PullRequest(['skip-artifact-caching-proxy']))
    def isOK = false
    script.withArtifactCachingProxy() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
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
    // Mock an available artifact caching proxy
    env.MAVEN_SETTINGS = '/tmp/settings.xml'
    // when running with useArtifactCachingProxy set to true
    script.runMaven(['clean verify'], 11, null, null, true)
    printCallStack()
    // then it does notice a withEnv call with the MAVEN_ARGS env var containing settings.xml path
    assertTrue(assertMethodCallContainsPattern('withEnv', 'MAVEN_ARGS=-s /tmp/settings.xml'))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testRunMavenWithArtifactCachingProxyDisabled() throws Exception {
    def script = loadScript(scriptName)
    // when running with useArtifactCachingProxy set to false
    script.runMaven(['clean verify'], 11, null, null, false)
    printCallStack()
    // then it does not notice a withEnv call with the MAVEN_ARGS env var containing settings.xml path
    assertFalse(assertMethodCallContainsPattern('withEnv', 'MAVEN_ARGS=-s /tmp/settings.xml'))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithFileShareServicePrincipal() throws Exception {
    // When used on infra.ci.jenkins.io
    helper.registerAllowedMethod('isInfra', [], { true })
    helper.registerAllowedMethod('sh', [Map.class], { m ->
      return "https://${defaultFileShareStorageAccount}.file.core.windows.net/${defaultFileShare}?sas-token"
    })
    def script = loadScript(scriptName)
    def isOK = false
    def options = [
      servicePrincipalCredentialsId: defaultServicePrincipalCredentialsId,
      fileShare: defaultFileShare,
      fileShareStorageAccount: defaultFileShareStorageAccount
    ]
    script.withFileShareServicePrincipal(options) {
      isOK = true
    }
    printCallStack()
    // then the correct Azure Service Principal credentials is used
    assertTrue(assertMethodCallContainsPattern('azureServicePrincipal', "credentialsId=${defaultServicePrincipalCredentialsId}"))
    // then the correct options are passed as env vars
    assertTrue(assertMethodCallContainsPattern('withEnv', "STORAGE_NAME=${defaultFileShareStorageAccount}, STORAGE_FILESHARE=${defaultFileShare}, STORAGE_DURATION_IN_MINUTE=${defaultTokenDuration}, STORAGE_PERMISSIONS=${defaultTokenPermissions}"))
    // then a script to get a file share signed URL is called
    assertTrue(assertMethodCallOccurrences('sh', 1))
    // then it sets $FILESHARE_SIGNED_URL to the signed file share URL
    assertTrue(assertMethodCallContainsPattern('withEnv', "FILESHARE_SIGNED_URL=https://${defaultFileShareStorageAccount}.file.core.windows.net/${defaultFileShare}?sas-token"))
    // then it inform about the URL expiring in the default amount of minutes
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: ${defaultFileShare} file share signed URL expiring in ${defaultTokenDuration} minute(s) available in \$FILESHARE_SIGNED_URL"))
    // then the body closure is executed
    assertTrue(isOK)
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithFileShareServicePrincipalWithMissingRequiredOption() throws Exception {
    // When used on infra.ci.jenkins.io
    helper.registerAllowedMethod('isInfra', [], { true })
    def script = loadScript(scriptName)
    def isOK = false
    def options = [
      fileShare: defaultFileShare,
      fileShareStorageAccount: defaultFileShareStorageAccount
    ]
    script.withFileShareServicePrincipal(options) {
      isOK = true
    }
    printCallStack()
    // then an error message is displayed
    assertTrue(assertMethodCallContainsPattern('echo', 'ERROR: At least one of these required options is missing: servicePrincipalCredentialsId, fileShare, fileShareStorageAccount'))
    // then the correct Azure Service Principal credentials is not used
    assertFalse(assertMethodCallContainsPattern('azureServicePrincipal', "credentialsId=${defaultServicePrincipalCredentialsId}"))
    // then the correct options are not passed as env vars
    assertFalse(assertMethodCallContainsPattern('withEnv', "STORAGE_NAME=${defaultFileShareStorageAccount}, STORAGE_FILESHARE=${defaultFileShare}, STORAGE_DURATION_IN_MINUTE=${defaultTokenDuration}, STORAGE_PERMISSIONS=${defaultTokenPermissions}"))
    // then a script to get a file share signed URL is not called
    assertFalse(assertMethodCallOccurrences('sh', 1))
    // then it doesn't set $FILESHARE_SIGNED_URL to the signed file share URL
    assertFalse(assertMethodCallContainsPattern('withEnv', "FILESHARE_SIGNED_URL="))
    // then it doesn't inform about the URL expiring in the default amount of minutes
    assertFalse(assertMethodCallContainsPattern('echo', "INFO: ${defaultFileShare} file share signed URL expiring in ${defaultTokenDuration} minute(s) available in \$FILESHARE_SIGNED_URL"))
    // then the body closure is not executed
    assertFalse(isOK)
    // then it doesn't succeeds
    assertJobStatusFailure()
  }

  @Test
  void testWithFileShareServicePrincipalShouldNotRunOutsideInfraOrTrusted() throws Exception {
    // When not used on infra.ci.jenkins.io or trusted.ci.jenkins.io
    helper.registerAllowedMethod('isInfra', [], { false })
    helper.registerAllowedMethod('isTrusted', [], { false })
    def script = loadScript(scriptName)
    def isOK = false
    def options = [
      servicePrincipalCredentialsId: defaultServicePrincipalCredentialsId,
      fileShare: defaultFileShare,
      fileShareStorageAccount: defaultFileShareStorageAccount
    ]
    script.withFileShareServicePrincipal(options) {
      isOK = true
    }
    printCallStack()
    // then an error message is displayed
    assertTrue(assertMethodCallContainsPattern('echo', 'ERROR: Cannot be used outside of infra.ci.jenkins.io or trusted.ci.jenkins.io'))
    // then the correct Azure Service Principal credentials is not used
    assertFalse(assertMethodCallContainsPattern('azureServicePrincipal', "credentialsId=${defaultServicePrincipalCredentialsId}"))
    // then the correct options are not passed as env vars
    assertFalse(assertMethodCallContainsPattern('withEnv', "STORAGE_NAME=${defaultFileShareStorageAccount}, STORAGE_FILESHARE=${defaultFileShare}, STORAGE_DURATION_IN_MINUTE=${defaultTokenDuration}, STORAGE_PERMISSIONS=${defaultTokenPermissions}"))
    // then a script to get a file share signed URL is not called
    assertFalse(assertMethodCallOccurrences('sh', 1))
    // then it doesn't set $FILESHARE_SIGNED_URL to the signed file share URL
    assertFalse(assertMethodCallContainsPattern('withEnv', "FILESHARE_SIGNED_URL="))
    // then it doesn't inform about the URL expiring in the default amount of minutes
    assertFalse(assertMethodCallContainsPattern('echo', "INFO: ${defaultFileShare} file share signed URL expiring in ${defaultTokenDuration} minute(s) available in \$FILESHARE_SIGNED_URL"))
    // then the body closure is not executed
    assertFalse(isOK)
    // then it doesn't succeeds
    assertJobStatusFailure()
  }
}
