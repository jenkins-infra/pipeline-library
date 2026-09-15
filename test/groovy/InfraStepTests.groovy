import org.junit.Before
import org.junit.Ignore
import org.junit.Test

import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertEquals

import mock.PullRequest
import mock.Scm

class InfraStepTests extends BaseTest {
  static final String scriptName = "vars/infra.groovy"
  static final String artifactCachingProxyServer = 'azure-internal'
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
    helper.registerAllowedMethod('azureServicePrincipal', [Map.class], { m ->
      m
    })
    // Mimic real catchError semantics: swallow the exception thrown by the body and force the configured build result
    helper.registerAllowedMethod('catchError', [Map.class, Closure.class], { m, body ->
      try {
        body()
      } catch (e) {
        updateBuildStatus(m.buildResult ?: 'SUCCESS')
      }
    })
    // Github-deployments plugin step, not modeled by the test harness's default step registry
    helper.registerAllowedMethod('recordDeployment', [Object.class, Object.class, Object.class, Object.class, Object.class], { a, b, c, d, e ->
      null
    })
    helper.registerAllowedMethod('string', [Map.class], { m -> m })
  }

  void mockRepositoryUrl(String repositoryName) {
    binding.setProperty('scm', new Scm("https://github.com/jenkins-infra/${repositoryName}.git"))
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
    assertTrue(script.isTrustedCiController())
  }

  @Test
  void testIsRelease() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://release.ci.jenkins.io/'
    binding.setVariable('env', env)
    assertTrue(script.isReleaseCiController())
  }

  @Test
  void testIsInfra() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://infra.ci.jenkins.io/'
    binding.setVariable('env', env)
    assertTrue(script.isInfraCiController())
  }

  @Test
  void testWithContainerRegistry() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://infra.ci.jenkins.io/'
    def isOK = false
    script.withContainerRegistry() {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('sh', 'echo "${DOCKER_CONFIG_PSW}" | docker login "${CONTAINER_REGISTRY}" --username "${DOCKER_CONFIG_USR}" --password-stdin'))
  }

  @Test
  void testWithContainerRegistryOutsideInfra() throws Exception {
    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://foo/'
    def isOK = false
    try {
      script.withContainerRegistry() {
        isOK = true
      }
    } catch(e) {
      //NOOP
    }
    printCallStack()
    assertFalse(isOK)
    assertJobStatusFailure()
    printCallStack()
    assertTrue(assertMethodCallContainsPattern('error', 'Unknown Jenkins host (foo): cannot log-in to container registry.'))
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
  void testWithArtifactCachingProxyEnabledAndACPServerSet() throws Exception {
    def script = loadScript(scriptName)
    def isOK = false
    env.ARTIFACT_CACHING_PROXY_SERVERID = 'https://foo:1313'

    script.withArtifactCachingProxy() {
      isOK = true
    }

    printCallStack()
    assertTrue(isOK)
    // then an healthcheck is performed on the provider
    assertTrue(assertMethodCallContainsPattern('sh', healthCheckScriptSh) || assertMethodCallContainsPattern('bat', healthCheckScriptBat))
    // then it notices the use of the provided artifact caching server
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: using artifact caching proxy server '${env.ARTIFACT_CACHING_PROXY_SERVERID}'."))
    // and the configFileProvider is set with the provided ACP Server Id
    assertTrue(assertMethodCallContainsPattern('configFile', "fileId=${env.ARTIFACT_CACHING_PROXY_SERVERID}"))
    // and the configFileProvider is executed (OK is the mock)
    assertTrue(assertMethodCallContainsPattern('configFileProvider', '[OK]'))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxyEnabledAndEmptyACPServer() throws Exception {
    def script = loadScript(scriptName)
    def isOK = false

    script.withArtifactCachingProxy() {
      isOK = true
    }

    printCallStack()
    assertTrue(isOK)
    // then it notices the use of the default artifact caching provider
    assertTrue(assertMethodCallContainsPattern('echo', "WARNING: artifact caching proxy is enabled but the provided 'ARTIFACT_CACHING_PROXY_SERVERID' setup on the agent is empty, will use repo.jenkins-ci.org."))
    // then no configFileProvider are set
    assertFalse(assertMethodCallContainsPattern('configFileProvider', '[OK]'))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxyDisabled() throws Exception {
    def script = loadScript(scriptName)
    def isOK = false

    script.withArtifactCachingProxy(false) {
      isOK = true
    }
    printCallStack()
    assertTrue(isOK)
    // then there is no call to the configFileProvider correctly set
    assertFalse(assertMethodCallContainsPattern('configFileProvider', '[OK]'))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxyWithoutSkipArtifactCachingProxyOnPullRequest() throws Exception {
    def script = loadScript(scriptName)
    // when running on a pull request without a "skip-artifact-caching-proxy" label
    env.CHANGE_URL = changeUrl
    env.ARTIFACT_CACHING_PROXY_SERVERID = 'https://foo:1313'
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
    // and the configFileProvider is set with the provided ACP Server Id
    assertTrue(assertMethodCallContainsPattern('configFile', "fileId=${env.ARTIFACT_CACHING_PROXY_SERVERID}"))
    // and the configFileProvider is executed (OK is the mock)
    assertTrue(assertMethodCallContainsPattern('configFileProvider', '[OK]'))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxySkipArtifactCachingProxyOnPullRequest() throws Exception {
    def script = loadScript(scriptName)
    env.CHANGE_URL = changeUrl
    env.ARTIFACT_CACHING_PROXY_SERVERID = 'https://foo:1313'
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
    // and the configFileProvider is NOT set with the provided ACP Server Id
    assertFalse(assertMethodCallContainsPattern('configFile', "fileId=${env.ARTIFACT_CACHING_PROXY_SERVERID}"))
    // and the configFileProvider is NOT executed
    assertFalse(assertMethodCallContainsPattern('configFileProvider', '[OK]'))

    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxySkipArtifactCachingProxyOnBranchWithoutPullRequest() throws Exception {
    def script = loadScript(scriptName)
    env.CHANGE_URL = null
    env.ARTIFACT_CACHING_PROXY_SERVERID = 'https://foo:1313'
    binding.setProperty('pullRequest', new PullRequest(['skip-artifact-caching-proxy']))
    def isOK = false

    script.withArtifactCachingProxy() {
      isOK = true
    }

    printCallStack()
    assertTrue(isOK)
    // then it doesn't notice the skipping of artifact-caching-proxy
    assertFalse(assertMethodCallContainsPattern('echo', "INFO: the label 'skip-artifact-caching-proxy' has been applied to the pull request, will use repo.jenkins-ci.org"))
    // and the configFileProvider is set with the provided ACP Server Id
    assertTrue(assertMethodCallContainsPattern('configFile', "fileId=${env.ARTIFACT_CACHING_PROXY_SERVERID}"))
    // and the configFileProvider is executed (OK is the mock)
    assertTrue(assertMethodCallContainsPattern('configFileProvider', '[OK]'))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithArtifactCachingProxyUnReachableRequestedProvider() throws Exception {
    def script = loadScript(scriptName)
    env.ARTIFACT_CACHING_PROXY_SERVERID = 'https://foo:1313'
    helper.addShMock(healthCheckScriptSh, '', 1)
    helper.addBatMock(healthCheckScriptBat, '', 1)
    def isOK = false

    script.withArtifactCachingProxy() {
      isOK = true
    }

    printCallStack()
    assertTrue(isOK)
    // then an healthcheck is performed on the provider
    assertTrue(assertMethodCallContainsPattern('sh', healthCheckScriptSh) || assertMethodCallContainsPattern('bat', healthCheckScriptBat))
    // then it does not notice the provider isn't reachable and that it will fallback to repo.jenkins-ci.org
    assertTrue(assertMethodCallContainsPattern('echo', "WARNING: the artifact caching proxy server '${env.ARTIFACT_CACHING_PROXY_SERVERID}' isn't reachable, will use repo.jenkins-ci.org."))
    // then it notices the use of the provided artifact caching server
    assertFalse(assertMethodCallContainsPattern('echo', "INFO: using artifact caching proxy server '${env.ARTIFACT_CACHING_PROXY_SERVERID}'."))
    // and the configFileProvider is set with the provided ACP Server Id
    assertFalse(assertMethodCallContainsPattern('configFile', "fileId=${env.ARTIFACT_CACHING_PROXY_SERVERID}"))
    // and the configFileProvider is executed (OK is the mock)
    assertFalse(assertMethodCallContainsPattern('configFileProvider', '[OK]'))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testRunMavenWithArtifactCachingProxy() throws Exception {
    def script = loadScript(scriptName)
    // Mock an available artifact caching proxy
    env.MAVEN_SETTINGS = '/tmp/settings.xml'
    env.ARTIFACT_CACHING_PROXY_SERVERID = 'https://foo:1313'

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
    helper.registerAllowedMethod('isInfraCiController', [], { true })
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
    // then the Azure Service Principal from the credentials passed in options is used
    assertTrue(assertMethodCallContainsPattern('azureServicePrincipal', "credentialsId=${defaultServicePrincipalCredentialsId}"))
    // then the correct options are passed as env vars
    assertTrue(assertMethodCallContainsPattern('withEnv', "STORAGE_NAME=${defaultFileShareStorageAccount}, STORAGE_FILESHARE=${defaultFileShare}, STORAGE_DURATION_IN_MINUTE=${defaultTokenDuration}, STORAGE_PERMISSIONS=${defaultTokenPermissions}"))
    // then a script to get a file share signed URL is called
    assertTrue(assertMethodCallOccurrences('sh', 1))
    // then it sets $FILESHARE_SIGNED_URL to the signed file share URL
    assertTrue(assertMethodCallContainsPattern('withEnv', "FILESHARE_SIGNED_URL=https://${defaultFileShareStorageAccount}.file.core.windows.net/${defaultFileShare}?sas-token"))
    // then it inform about the signed URL expiring in the default amount of minutes available in $FILESHARE_SIGNED_URL
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: ${defaultFileShare} file share signed URL expiring in ${defaultTokenDuration} minute(s) available in \$FILESHARE_SIGNED_URL"))
    // then the body closure is executed
    assertTrue(isOK)
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithFileShareServicePrincipalWithMissingRequiredOption() throws Exception {
    // When used on infra.ci.jenkins.io
    helper.registerAllowedMethod('isInfraCiController', [], { true })
    def script = loadScript(scriptName)
    def isOK = false
    // with missing fileShareStorageAccount option
    def options = [
      servicePrincipalCredentialsId: defaultServicePrincipalCredentialsId,
      fileShare: defaultFileShare
    ]
    script.withFileShareServicePrincipal(options) {
      isOK = true
    }
    printCallStack()
    // then an error message is displayed
    assertTrue(assertMethodCallContainsPattern('echo', 'ERROR: At least one of these required options is missing: fileShare, fileShareStorageAccount'))
    // then the Azure Service Principal from the credentials passed in options is not used
    assertFalse(assertMethodCallContainsPattern('azureServicePrincipal', "credentialsId=${defaultServicePrincipalCredentialsId}"))
    // then the correct options are not passed as env vars
    assertFalse(assertMethodCallContainsPattern('withEnv', "STORAGE_NAME=${defaultFileShareStorageAccount}, STORAGE_FILESHARE=${defaultFileShare}, STORAGE_DURATION_IN_MINUTE=${defaultTokenDuration}, STORAGE_PERMISSIONS=${defaultTokenPermissions}"))
    // then a script to get a file share signed URL is not called
    assertFalse(assertMethodCallOccurrences('sh', 1))
    // then it doesn't set $FILESHARE_SIGNED_URL to the signed file share URL
    assertFalse(assertMethodCallContainsPattern('withEnv', "FILESHARE_SIGNED_URL="))
    // then it doesn't inform neither about the signed URL expiring in the default amount of minutes available in $FILESHARE_SIGNED_URL
    assertFalse(assertMethodCallContainsPattern('echo', "INFO: ${defaultFileShare} file share signed URL expiring in ${defaultTokenDuration} minute(s) available in \$FILESHARE_SIGNED_URL"))
    // nor about the credential-less, azcopy logged in, and the URL available in $FILESHARE_SIGNED_URL
    assertFalse(assertMethodCallContainsPattern('echo', "INFO: credential-less (using user assigned identity service principal), azcopy logged in and ${defaultFileShare} file share URL available in \$FILESHARE_SIGNED_URL"))
    // then the body closure is not executed
    assertFalse(isOK)
    // then it doesn't succeeds
    assertJobStatusFailure()
  }

  @Test
  void testWithFileShareServicePrincipalCredentialsLess() throws Exception {
    // When used on infra.ci.jenkins.io
    helper.registerAllowedMethod('isInfraCiController', [], { true })
    helper.registerAllowedMethod('sh', [Map.class], { m ->
      return "https://${defaultFileShareStorageAccount}.file.core.windows.net/${defaultFileShare}?sas-token"
    })
    def script = loadScript(scriptName)
    def isOK = false
    // without any servicePrincipalCredentialsId option
    def options = [
      fileShare: defaultFileShare,
      fileShareStorageAccount: defaultFileShareStorageAccount
    ]
    script.withFileShareServicePrincipal(options) {
      isOK = true
    }
    printCallStack()
    // then no Azure Service Principal from the credentials (not) passed in options is used
    assertFalse(assertMethodCallContainsPattern('azureServicePrincipal', 'credentialsId='))
    // then the correct options are passed as env vars
    assertTrue(assertMethodCallContainsPattern('withEnv', "STORAGE_NAME=${defaultFileShareStorageAccount}, STORAGE_FILESHARE=${defaultFileShare}, STORAGE_DURATION_IN_MINUTE=${defaultTokenDuration}, STORAGE_PERMISSIONS=${defaultTokenPermissions}"))
    // then a script to get a file share signed URL is called
    assertTrue(assertMethodCallOccurrences('sh', 1))
    // then it sets $FILESHARE_SIGNED_URL to the signed file share URL
    assertTrue(assertMethodCallContainsPattern('withEnv', "FILESHARE_SIGNED_URL=https://${defaultFileShareStorageAccount}.file.core.windows.net/${defaultFileShare}?sas-token"))
    // then it inform about the credential-less, azcopy logged in, and the URL available in $FILESHARE_SIGNED_URL
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: credential-less (using user assigned identity service principal), azcopy logged in and ${defaultFileShare} file share URL available in \$FILESHARE_SIGNED_URL"))
    // then the body closure is executed
    assertTrue(isOK)
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void testWithFileShareServicePrincipalShouldNotRunOutsideInfraOrTrusted() throws Exception {
    // When not used on infra.ci.jenkins.io or trusted.ci.jenkins.io
    helper.registerAllowedMethod('isInfraCiController', [], { false })
    helper.registerAllowedMethod('isTrustedCiController', [], { false })
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

  @Test
  void testGetBuildAgentLabel() throws Exception {
    def script = loadScript(scriptName)

    def cases = [
      // container agents
      [platform: 'linux', jdk: '21', container: true, expected: 'maven-21', warning: null],
      [platform: 'windows', jdk: '17', container: true, expected: 'maven-17-windows', warning: null],
      // VM agents
      [platform: 'linux', jdk: '8', container: false, expected: 'vm && linux', warning: null],
      [platform: 'windows', jdk: '8', container: false, expected: 'windows-2025', warning: null],
      // unknown platform
      [platform: 'openbsd', jdk: '11', container: false, expected: 'openbsd', warning: 'vm'],
      [platform: 'openbsd', jdk: '11', container: true, expected: 'openbsd', warning: 'container'],
      // docker controller and agents jobs
      [
        // linux image
        platform: 'docker-highmem', jdk: '', container: false,
        expected: 'docker-highmem && spot', warning: null
      ],
      [
        // linux image built on trusted.ci.jenkins.io
        platform: 'docker-highmem', jdk: '', container: false, trustedEnv: true,
        expected: 'linux', warning: null
      ],
      [
        // windows 2025 image
        platform: 'windows-2025', jdk: '', container: false,
        expected: 'windows-2025 && spot', warning: null
      ],
      [
        // windows 2019 image built on trusted.ci.jenkins.io
        platform: 'windows-2019', jdk: '', container: false, trustedEnv: true,
        expected: 'windows-2019', warning: null
      ],
      [
        // linux image first run
        platform: 'docker-highmem', jdk: '', container: false, retry: 0,
        expected: 'docker-highmem && spot', warning: null
      ],
      [
        // linux image third run (second retry after the first run)
        platform: 'docker-highmem', jdk: '', container: false, retry: 2,
        expected: 'docker-highmem && nonspot', warning: null
      ],
      [
        // windows 2022 image third run (second retry after the first run)
        platform: 'windows-2022', jdk: '', container: false, retry: 2,
        expected: 'windows-2022 && nonspot', warning: null
      ],
      [
        // linux image built on trusted.ci.jenkins.io third run (second retry after the first run)
        platform: 'docker-highmem', jdk: '', container: false, trustedEnv: true, retry: 2,
        expected: 'linux', warning: null
      ],
      [
        // windows 2025 image built on trusted.ci.jenkins.io third run (second retry after the first run)
        platform: 'windows-2025', jdk: '', container: false, trustedEnv: true, retry: 2,
        expected: 'windows-2025', warning: null
      ],
    ]

    cases.each { c ->
      // reset call stack between cases
      clearCallStack()

      // default values
      def spotRetryCounter = c.containsKey('retry') ? c.retry : null
      // environment (trusted.ci.jenkins.io or not)
      env.JENKINS_URL = (c.containsKey('trustedEnv') && c.trustedEnv) ? 'https://trusted.ci.jenkins.io:1443/' : 'https://ci.jenkins.io/'
      binding.setVariable('env', env)

      String result = script.getBuildAgentLabel([
        useContainerAgent: c.container,
        platform: c.platform,
        jdk: c.jdk,
        spotRetryCounter: spotRetryCounter
      ])
      printCallStack()

      assertEquals("Unexpected result for case: ${c}", c.expected, result)

      if (c.warning == null) {
        assertFalse("Did not expect a warning for case: ${c}", assertMethodCallContainsPattern('echo', 'WARNING:'))
      }
      if (c.warning == 'vm') {
        assertTrue("Expected VM warning for case: ${c}", assertMethodCallContainsPattern('echo', 'Unknown Virtual Machine platform'))
      }
      if (c.warning == 'container') {
        assertTrue("Expected container warning for case: ${c}", assertMethodCallContainsPattern('echo', 'Unknown container platform'))
      }

      if (c.containsKey('trustedEnv') && c.trustedEnv) {
        assertMethodCallContainsPattern('echo', 'running on trusted.ci.jenkins.io')
      }
      if (c.containsKey('retry') && c.retry> 1) {
        assertMethodCallContainsPattern('echo', 'more than one retry, using "nonspot" agent')
      }
    }

    assertJobStatusSuccess()
  }

  @Test
  void testGetBuildWebsiteAgentLabel() throws Exception {
    def script = loadScript(scriptName)

    def cases = [
      // ci.jenkins.io: maven agent, only "-nonspot" suffix, no " && spot" nor " && nonspot"
      [url: 'https://ci.jenkins.io/', retry: 0, expected: 'maven-25'],
      [url: 'https://ci.jenkins.io/', retry: 1, expected: 'maven-25'],
      [url: 'https://ci.jenkins.io/', retry: 2, expected: 'maven-25-nonspot'],
      // infra.ci.jenkins.io and trusted.ci.jenkins.io: nonspot by default, no "spot"
      [url: 'https://infra.ci.jenkins.io/', retry: 0, expected: 'linux-arm64-docker'],
      [url: 'https://infra.ci.jenkins.io/', retry: 2, expected: 'linux-arm64-docker'],
      [url: 'https://trusted.ci.jenkins.io/', retry: 0, expected: 'linux-arm64-docker'],
      // Any other controller: arm64 docker VM agent, explicit "spot"/"nonspot" suffix
      [url: 'https://foo.jenkins.io/', retry: 0, expected: 'linux-arm64-docker && spot'],
      [url: 'https://foo.jenkins.io/', retry: 2, expected: 'linux-arm64-docker && nonspot'],
    ]

    cases.each { c ->
      clearCallStack()
      env.JENKINS_URL = c.url
      binding.setVariable('env', env)

      String result = script.getBuildWebsiteAgentLabel(c.retry)
      printCallStack()

      assertEquals("Unexpected result for case: ${c}", c.expected, result)
    }

    assertJobStatusSuccess()
  }

  @Test
  void testMaybeWebsitePreBuildCommandRunsTheConfiguredCommand() throws Exception {
    def script = loadScript(scriptName)
    // 'stats.jenkins.io' is configured with a preBuildCommand
    mockRepositoryUrl('stats.jenkins.io')
    helper.registerAllowedMethod('readTrusted', [String.class], { f ->
      "trusted content of ${f}"
    })

    script.maybeWebsitePreBuildCommand()
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('readTrusted', 'retrieve-infra-statistics-data.sh'))
    assertTrue(assertMethodCallContainsPattern('writeFile', 'file=retrieve-infra-statistics-data.sh'))
    assertTrue(assertMethodCallContainsPattern('writeFile', 'text=trusted content of retrieve-infra-statistics-data.sh'))
    assertTrue(assertMethodCallContainsPattern('sh', 'INFRASTATISTICS_LOCATION=src/data/infra-statistics ./retrieve-infra-statistics-data.sh'))
    assertJobStatusSuccess()
  }

  @Test
  void testMaybeWebsitePreBuildCommandDoesNothingWithoutConfiguredCommand() throws Exception {
    def script = loadScript(scriptName)
    // 'stories' has no preBuildCommand configured
    mockRepositoryUrl('stories')

    script.maybeWebsitePreBuildCommand()
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('echo', "No prebuild command to execute for 'stories'"))
    assertFalse(assertMethodCall('sh'))
    assertJobStatusSuccess()
  }

  @Test
  void testDeployWebsiteSkipsOnCiController() throws Exception {
    def script = loadScript(scriptName)
    mockRepositoryUrl('stats.jenkins.io')
    env.JENKINS_URL = 'https://ci.jenkins.io/'

    script.deployWebsite('public')
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('error', 'Skipping: No deployment from ci.jenkins.io, only from a private controller'))
    assertJobStatusSuccess()
  }

  @Test
  void testDeployWebsiteSkipsWithoutDeployFolder() throws Exception {
    def script = loadScript(scriptName)
    mockRepositoryUrl('stats.jenkins.io')
    env.JENKINS_URL = 'https://foo.jenkins.io/'

    script.deployWebsite('')
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('error', 'Skipping: A public folder is required to deploy a website'))
    assertJobStatusSuccess()
  }

  @Test
  void testDeployWebsiteSkipsWithDotPrefixedDeployFolder() throws Exception {
    def script = loadScript(scriptName)
    mockRepositoryUrl('stats.jenkins.io')
    env.JENKINS_URL = 'https://foo.jenkins.io/'

    script.deployWebsite('.hidden')
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('error', 'Skipping: The public folder can\'t start with a dot'))
    assertJobStatusSuccess()
  }

  @Test
  void testDeployWebsiteDoesNothingOutsidePullRequestOrPrimaryBranch() throws Exception {
    def script = loadScript(scriptName)
    mockRepositoryUrl('stats.jenkins.io')
    env.JENKINS_URL = 'https://foo.jenkins.io/'

    script.deployWebsite('public')
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('echo', 'Neither on a pull request nor on primary branch, no deployment'))
    assertFalse(assertMethodCallContainsPattern('sh', 'netlify-deploy'))
    assertFalse(assertMethodCallContainsPattern('sh', 'azcopy sync'))
    assertJobStatusSuccess()
  }

  @Test
  void testDeployWebsiteOnPullRequestDeploysDraftToNetlify() throws Exception {
    def script = loadScript(scriptName)
    mockRepositoryUrl('stats.jenkins.io')
    env.JENKINS_URL = 'https://foo.jenkins.io/'
    env.CHANGE_ID = '42'
    binding.setProperty('pullRequest', new PullRequest([], 'pr-head-sha'))

    script.deployWebsite('public')
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('withCredentials', 'netlify-auth-token'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'NETLIFY_NAME=stats-jenkins-io'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'DRAFT=true'))
    assertTrue(assertMethodCallContainsPattern('sh', 'netlify-deploy'))
    assertTrue(assertMethodCallContainsPattern('recordDeployment', 'success'))
    // Not on the primary branch: no production dispatch on top of the preview
    assertFalse(assertMethodCallContainsPattern('sh', 'azcopy sync'))
    assertJobStatusSuccess()
  }

  @Test
  void testDeployWebsiteOnPullRequestSurvivesNetlifyFailure() throws Exception {
    def script = loadScript(scriptName)
    mockRepositoryUrl('stats.jenkins.io')
    env.JENKINS_URL = 'https://foo.jenkins.io/'
    env.CHANGE_ID = '42'
    binding.setProperty('pullRequest', new PullRequest([], 'pr-head-sha'))
    helper.registerAllowedMethod('sh', [String.class], { s ->
      if (s.startsWith('netlify-deploy')) {
        throw new Exception('netlify-deploy failed')
      }
      return s
    })

    script.deployWebsite('public')
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('recordDeployment', 'failure'))
    // A draft (preview) failure must not fail the overall build
    assertJobStatusSuccess()
  }

  @Test
  void testDeployWebsiteOnPrimaryBranchDeploysToNetlifyWhenConfigured() throws Exception {
    def script = loadScript(scriptName)
    // 'jenkins-io-components' is configured with `deployProductionToNetlify: true`
    mockRepositoryUrl('jenkins-io-components')
    env.JENKINS_URL = 'https://foo.jenkins.io/'
    env.BRANCH_IS_PRIMARY = true
    binding.setProperty('pullRequest', new PullRequest([], 'pr-head-sha'))

    script.deployWebsite('public')
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('withEnv', 'NETLIFY_NAME=jenkins-io-components'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'DRAFT=false'))
    assertFalse(assertMethodCallContainsPattern('sh', 'azcopy sync'))
    assertJobStatusSuccess()
  }

  @Test
  void testDeployWebsiteOnPrimaryBranchDeploysToAzureFileShareByDefault() throws Exception {
    def script = loadScript(scriptName)
    // 'stats.jenkins.io' has no `deployProductionToNetlify` in its website config
    mockRepositoryUrl('stats.jenkins.io')
    // withFileShareServicePrincipal is only usable from infra.ci.jenkins.io or trusted.ci.jenkins.io
    env.JENKINS_URL = 'https://infra.ci.jenkins.io/'
    env.BRANCH_IS_PRIMARY = true
    helper.registerAllowedMethod('sh', [Map.class], { m ->
      'https://statsjenkinsio.file.core.windows.net/stats-jenkins-io?sas-token'
    })

    script.deployWebsite('public')
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('sh', 'azcopy sync'))
    // 'stats.jenkins.io' is configured with a service principal credentials id
    assertTrue(assertMethodCallContainsPattern('withCredentials', 'infraci-stats-jenkins-io-fileshare-service-principal-writer'))
    assertJobStatusSuccess()
  }

  @Test
  void testDeployWebsiteOnPrimaryBranchFailsBuildOnAzureFileShareFailure() throws Exception {
    def script = loadScript(scriptName)
    mockRepositoryUrl('stats.jenkins.io')
    // withFileShareServicePrincipal is only usable from infra.ci.jenkins.io or trusted.ci.jenkins.io
    env.JENKINS_URL = 'https://infra.ci.jenkins.io/'
    env.BRANCH_IS_PRIMARY = true
    helper.registerAllowedMethod('sh', [Map.class], { m ->
      'https://statsjenkinsio.file.core.windows.net/stats-jenkins-io?sas-token'
    })
    helper.registerAllowedMethod('sh', [String.class], { s ->
      if (s.contains('azcopy sync')) {
        throw new Exception('azcopy failed')
      }
      return s
    })

    try {
      script.deployWebsite('public')
    } catch (e) {
      // NOOP: a production Azure File Share failure is expected to fail the build
    }
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('error', 'Failure during the synchronization to Azure File Share'))
    assertJobStatusFailure()
  }
}
