import mock.CurrentBuild
import mock.Infra
import org.junit.Before
import org.junit.Test
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertNotNull
import static org.junit.Assert.assertTrue

class BuildPluginStepTests extends BaseTest {
  static final String scriptName = 'vars/buildPlugin.groovy'
  static final String defaultArtifactCachingProxyProvider = 'azure'
  static final String anotherArtifactCachingProxyProvider = 'do'
  static final String invalidArtifactCachingProxyProvider = 'foo'
  static final String healthCheckScriptSh = 'curl --fail --silent --show-error --location $HEALTHCHECK'
  static final String healthCheckScriptBat = 'curl --fail --silent --show-error --location %HEALTHCHECK%'
  static final String changeUrlWithSkipACPLabel = 'https://api.github.com/repos/jenkins-infra/pipeline-library/pull/123'
  static final String prLabelsContainSkipACPScriptSh = 'curl --silent -H "Accept: application/vnd.github+json" -H "Authorization: Bearer $GH_TOKEN" https://api.github.com/repos/jenkins-infra/pipeline-library/issues/123/labels | grep --ignore-case "skip-artifact-caching-proxy"'
  static final String prLabelsContainSkipACPScriptBat = 'curl --silent -H "Accept: application/vnd.github+json" -H "Authorization: Bearer %GH_TOKEN%" https://api.github.com/repos/jenkins-infra/pipeline-library/issues/123/labels | findstr /i "skip-artifact-caching-proxy"'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
    // It is expected to be on Maven by default. Override this behavior when you need to specialize
    helper.registerAllowedMethod('fileExists', [String.class], { s -> return s.equals('pom.xml') })
    env.NODE_LABELS = 'docker'
    env.JOB_NAME = 'build/plugin/test'
    // Testing by default on the primary branch
    env.BRANCH_IS_PRIMARY = true
  }

  @Test
  void test_getConfigurations_with_implicit_and_explicit() throws Exception {
    def script = loadScript(scriptName)
    try {
      // parameters are set to random values
      script.getConfigurations(configurations: true, platforms: true)
    } catch(e) {
      //NOOP
    }
    printCallStack()
    assertTrue(assertMethodCallContainsPattern('error', 'can not be used'))
    assertJobStatusFailure()
  }

  @Test
  void test_getConfigurations_explicit_without_platform() throws Exception {
    def script = loadScript(scriptName)
    try {
      script.getConfigurations(configurations: [[ jdk: '1.8' ]])
    } catch(e) {
      //NOOP
    }
    printCallStack()
    assertTrue(assertMethodCallContainsPattern('error', 'Configuration field "platform" must be specified: [jdk:1.8]'))
    assertJobStatusFailure()
  }

  @Test
  void test_getConfigurations_explicit_without_jdk() throws Exception {
    def script = loadScript(scriptName)
    try {
      script.getConfigurations(configurations: [[ platform: 'linux' ]])
    } catch(e) {
      //NOOP
    }
    printCallStack()
    assertTrue(assertMethodCallContainsPattern('error','Configuration field "jdk" must be specified: [platform:linux]'))
    assertJobStatusFailure()
  }

  @Test
  void test_getConfigurations_without_parameters() throws Exception {
    def script = loadScript(scriptName)
    def configurations = script.getConfigurations([:])

    def expected = [
      ['platform': 'linux', 'jdk': '8', 'jenkins': null],
      ['platform': 'windows', 'jdk': '8', 'jenkins': null],
    ]
    assertEquals(expected, configurations)
    printCallStack()
    assertJobStatusSuccess()
  }

  @Test
  void test_getConfigurations_implicit_with_platforms() throws Exception {
    def script = loadScript(scriptName)
    def configurations = script.getConfigurations(platforms: ['bar', 'foo'])
    println configurations
    assertEquals(configurations.size, 2)
    assertNotNull(configurations.find {it.platform.equals('bar')})
    assertNotNull(configurations.find {it.platform.equals('foo')})
    printCallStack()
    assertJobStatusSuccess()
  }

  @Test
  void test_getConfigurations_implicit_with_jenkinsVersions() throws Exception {
    def script = loadScript(scriptName)
    def configurations = script.getConfigurations(jenkinsVersions: ['1.x', '2.x'])
    assertEquals(configurations.size, 4)
    assertNotNull(configurations.find{it.jenkins.equals('1.x')})
    assertNotNull(configurations.find{it.jenkins.equals('2.x')})
    printCallStack()
    assertJobStatusSuccess()
  }

  @Test
  void test_getConfigurations_implicit_with_jdkVersions() throws Exception {
    def script = loadScript(scriptName)
    def configurations = script.getConfigurations(jdkVersions: ['1.4', '1.3'])
    assertEquals(configurations.size, 4)
    assertNotNull(configurations.find{it.jdk.equals('1.4')})
    assertNotNull(configurations.find{it.jdk.equals('1.3')})
    printCallStack()
    assertJobStatusSuccess()
  }

  @Test
  void test_hasDockerLabel() throws Exception {
    def script = loadScript(scriptName)
    def value = script.hasDockerLabel()
    printCallStack()
    assertTrue(value)
  }

  @Test
  void test_buildPlugin_with_defaults() throws Exception {
    def script = loadScript(scriptName)
    // when running without any parameters
    script.call([:])
    printCallStack()
    // then it runs in a linux node
    assertTrue(assertMethodCallContainsPattern('node', 'linux'))
    // then it runs in a windows node
    assertTrue(assertMethodCallContainsPattern('node', 'windows'))
    // then it runs the junit step by default
    assertTrue(assertMethodCall('junit'))
    // then it runs the junit step with the maven test format
    assertTrue(assertMethodCallContainsPattern('junit', '**/target/surefire-reports/**/*.xml,**/target/failsafe-reports/**/*.xml'))
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_timeout() throws Exception {
    def script = loadScript(scriptName)
    script.call(timeout: 300)
    printCallStack()
    assertTrue(assertMethodCallContainsPattern('echo', 'lowering to 180'))
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_without_tests() throws Exception {
    def script = loadScript(scriptName)
    script.call(tests: [skip: true])
    printCallStack()
    // the junit step is disabled
    assertFalse(assertMethodCall('junit'))
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_build_error() throws Exception {
    def script = loadScript(scriptName)
    binding.setProperty('infra', new Infra(buildError: true))
    try {
      script.call([:])
    } catch (ignored) {
      // intentionally left empty
    }
    printCallStack()
    // it runs the junit step
    assertTrue(assertMethodCall('junit'))
    assertJobStatusFailure()
  }

  @Test
  void test_buildPlugin_with_defaults_with_gradle() throws Exception {
    def script = loadScript(scriptName)
    // when running in a non maven project
    helper.registerAllowedMethod('fileExists', [String.class], { s -> return !s.equals('pom.xml') })
    script.call([:])
    printCallStack()
    // then it runs the junit step with the no maven test format
    assertTrue(assertMethodCallContainsPattern('junit', '**/build/test-results/**/*.xml'))
  }

  @Test
  void test_buildPlugin_with_build_error_with_gradle() throws Exception {
    def script = loadScript(scriptName)
    binding.setProperty('infra', new Infra(buildError: true))
    // when running in a non maven project
    helper.registerAllowedMethod('fileExists', [String.class], { s -> return !s.equals('pom.xml') })
    try {
      script.call([:])
    } catch (ignored) {
      // intentionally left empty
    }
    printCallStack()
    // it runs the junit step
    assertTrue(assertMethodCall('junit'))
    assertJobStatusFailure()
  }

  @Test
  void test_buildPlugin_with_failfast_and_unstable() throws Exception {
    def script = loadScript(scriptName)
    // when running with fail fast and it's UNSTABLE
    binding.setProperty('currentBuild', new CurrentBuild('UNSTABLE'))
    try {
      script.call(failFast: true)
    } catch(e) {
      //NOOP
    }
    printCallStack()
    // then throw an error
    assertTrue(assertMethodCallContainsPattern('error', 'There were test failure'))
    assertJobStatusFailure()
  }

  @Test
  void test_buildPlugin_with_warnings_ng() throws Exception {
    def script = loadScript(scriptName)
    script.call()
    printCallStack()

    assertTrue(assertMethodCall('mavenConsole'))
    assertTrue(assertMethodCallContainsPattern('recordIssues', '{enabledForFailure=true, tool=maven, skipBlames=true, trendChartType=TOOLS_ONLY}'))

    assertTrue(assertMethodCall('java'))
    assertTrue(assertMethodCall('javaDoc'))
    assertTrue(assertMethodCallContainsPattern('recordIssues', '{enabledForFailure=true, tools=[java, javadoc], filters=[true], sourceCodeEncoding=UTF-8, skipBlames=true, trendChartType=TOOLS_ONLY}'))

    assertTrue(assertMethodCall('spotBugs'))
    assertTrue(assertMethodCallContainsPattern('recordIssues', '{tool=spotbugs, sourceCodeEncoding=UTF-8, skipBlames=true, trendChartType=TOOLS_ONLY, qualityGates=[{threshold=1, type=NEW, unstable=true}]}'))

    assertTrue(assertMethodCall('checkStyle'))
    assertTrue(assertMethodCallContainsPattern('recordIssues', '{tool=checkstyle, sourceCodeEncoding=UTF-8, skipBlames=true, trendChartType=TOOLS_ONLY, qualityGates=[{threshold=1, type=TOTAL, unstable=true}]}'))

    assertTrue(assertMethodCall('pmdParser'))
    assertTrue(assertMethodCallContainsPattern('recordIssues', '{tool=pmd, sourceCodeEncoding=UTF-8, skipBlames=true, trendChartType=NONE}'))

    assertTrue(assertMethodCall('cpd'))
    assertTrue(assertMethodCallContainsPattern('recordIssues', '{tool=cpd, sourceCodeEncoding=UTF-8, skipBlames=true, trendChartType=NONE}'))


    assertTrue(assertMethodCallContainsPattern('taskScanner', '{includePattern=**/*.java, excludePattern=**/target/**, highTags=FIXME, normalTags=TODO}'))
    assertTrue(assertMethodCallContainsPattern('recordIssues', '{enabledForFailure=true, tool=tasks, sourceCodeEncoding=UTF-8, skipBlames=true, trendChartType=NONE}'))
  }

  @Test
  void test_buildPlugin_with_warnings_ng_and_thresholds() throws Exception {
    def script = loadScript(scriptName)
    script.call(spotbugs: [
      qualityGates: [
        [threshold: 3, type: 'TOTAL', unstable: true],
        [threshold: 4, type: 'NEW', unstable: true],
      ],
      sourceCodeEncoding: 'UTF-16'])
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('recordIssues', '{tool=spotbugs, sourceCodeEncoding=UTF-16, skipBlames=true, trendChartType=TOOLS_ONLY, qualityGates=[{threshold=3, type=TOTAL, unstable=true}, {threshold=4, type=NEW, unstable=true}]}'))
  }

  @Test
  void test_buildPlugin_with_warnings_ng_and_checkstyle() throws Exception {
    def script = loadScript(scriptName)
    script.call(checkstyle: [
      qualityGates: [
        [threshold: 3, type: 'TOTAL', unstable: true],
        [threshold: 4, type: 'NEW', unstable: true],
      ],
      filters: '[includeFile(\'MyFile.*.java\'), excludeCategory(\'WHITESPACE\')]'])
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('recordIssues', '{tool=checkstyle, sourceCodeEncoding=UTF-8, skipBlames=true, trendChartType=TOOLS_ONLY, qualityGates=[{threshold=3, type=TOTAL, unstable=true}, {threshold=4, type=NEW, unstable=true}], filters=[includeFile(\'MyFile.*.java\'), excludeCategory(\'WHITESPACE\')]}'))
  }

  @Test
  void test_buildPlugin_with_warnings_ng_and_pmd_chart() throws Exception {
    def script = loadScript(scriptName)
    script.call(pmd: [trendChartType: 'TOOLS_ONLY'])
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('recordIssues', '{tool=pmd, sourceCodeEncoding=UTF-8, skipBlames=true, trendChartType=TOOLS_ONLY}'))
  }

  @Test
  void test_buildPlugin_with_warnings_ng_and_cpd() throws Exception {
    def script = loadScript(scriptName)
    script.call(cpd: [enabledForFailure: true])
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('recordIssues', '{tool=cpd, sourceCodeEncoding=UTF-8, skipBlames=true, trendChartType=NONE, enabledForFailure=true}'))
  }

  @Test
  void test_buildPlugin_with_coverage() throws Exception {
    def script = loadScript(scriptName)
    script.call()
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('publishCoverage', '{calculateDiffForChangeRequests=true, adapters=[jacoco]}'))
  }

  @Test
  void test_buildPlugin_with_configurations_and_incrementals() throws Exception {
    def script = loadScript(scriptName)
    // when running with incrementals
    helper.registerAllowedMethod('fileExists', [String.class], { s ->
      return s.equals('.mvn/extensions.xml') || s.equals('pom.xml')
    })
    helper.addReadFileMock('.mvn/extensions.xml', 'git-changelist-maven-extension')
    // and no jenkins version
    script.call(configurations: [['platform': 'linux', 'jdk': 8, 'jenkins': null]])
    printCallStack()
    // then it runs the fingerprint
    assertTrue(assertMethodCallContainsPattern('fingerprint', '**/*-rc*.*/*-rc*.*'))
  }

  @Test
  void test_buildPlugin_with_artifact_caching_proxy_enabled_and_no_provider_specified() throws Exception {
    def script = loadScript(scriptName)
    // when running with artifactCachingProxyEnabled set to true and no provider is specified
    script.call(['artifactCachingProxyEnabled': true])
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
  void test_buildPlugin_with_artifact_caching_proxy_enabled_and_empty_provider_specified() throws Exception {
    def script = loadScript(scriptName)
    // when running with artifactCachingProxyEnabled set to true and an empty provider is specified
    env.ARTIFACT_CACHING_PROXY_PROVIDER = ''
    script.call(['artifactCachingProxyEnabled': true])
    printCallStack()
    // then an healthcheck is performed on the provider
    assertTrue(assertMethodCallContainsPattern('sh', healthCheckScriptSh) || assertMethodCallContainsPattern('bat', healthCheckScriptBat))
    // then it notices the use of the default artifact caching provider
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: using artifact caching proxy from '${defaultArtifactCachingProxyProvider}' provider."))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_artifact_caching_proxy_enabled_and_invalid_provider_specified() throws Exception {
    def script = loadScript(scriptName)
    // when running with artifactCachingProxyEnabled set to true and an invalid provider is specified
    env.ARTIFACT_CACHING_PROXY_PROVIDER = invalidArtifactCachingProxyProvider
    script.call(['artifactCachingProxyEnabled': true])
    printCallStack()
    // then it notices invalid or unavailable artifact caching provider has been specified and that it will fallback to repo.jenkins-ci.org
    assertTrue(assertMethodCallContainsPattern('echo', "WARNING: invalid or unavailable artifact caching proxy provider '${invalidArtifactCachingProxyProvider}' specified, will use repo.jenkins-ci.org"))
    // then there is no call to configFile containing the specified artifact caching proxy provider id
    assertFalse(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${invalidArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_artifact_caching_proxy_enabled_and_no_providers_env_var() throws Exception {
    def script = loadScript(scriptName)
    // when running with artifactCachingProxyEnabled set to true and an available provider different from the default one is specified
    // without ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS env var declared
    env.ARTIFACT_CACHING_PROXY_PROVIDER = anotherArtifactCachingProxyProvider
    env.ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS = null
    script.call(['artifactCachingProxyEnabled': true])
    printCallStack()
    // then it notices the use of the specified artifact caching provider
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: using artifact caching proxy from '${anotherArtifactCachingProxyProvider}' provider."))
    // then there is a call to configFile containing the specified artifact caching proxy provider id
    assertTrue(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${anotherArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_artifact_caching_proxy_enabled_and_empty_providers_env_var() throws Exception {
    def script = loadScript(scriptName)
    // when running with artifactCachingProxyEnabled set to true and an available provider different from the default one is specified
    env.ARTIFACT_CACHING_PROXY_PROVIDER = anotherArtifactCachingProxyProvider
    env.ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS = ''
    script.call(['artifactCachingProxyEnabled': true])
    printCallStack()
    // then it notices the use of the specified artifact caching provider
    assertTrue(assertMethodCallContainsPattern('echo', "INFO: using artifact caching proxy from '${anotherArtifactCachingProxyProvider}' provider."))
    // then there is a call to configFile containing the specified artifact caching proxy provider id
    assertTrue(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${anotherArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_artifact_caching_proxy_enabled_and_invalid_providers_env_var() throws Exception {
    def script = loadScript(scriptName)
    // when running with artifactCachingProxyEnabled set to true and an available provider different from the default one is specified
    // with an invalid provider configured with ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS env var declared
    env.ARTIFACT_CACHING_PROXY_PROVIDER = anotherArtifactCachingProxyProvider
    env.ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS = invalidArtifactCachingProxyProvider
    script.call(['artifactCachingProxyEnabled': true])
    printCallStack()
    // then it notices invalid or unavailable artifact caching provider has been specified and that it will fallback to repo.jenkins-ci.org
    assertTrue(assertMethodCallContainsPattern('echo', "WARNING: invalid or unavailable artifact caching proxy provider '${anotherArtifactCachingProxyProvider}' specified, will use repo.jenkins-ci.org"))
    // then there is no call to configFile containing the specified artifact caching proxy provider id
    assertFalse(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${anotherArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_artifact_caching_proxy_enabled_and_unavailable_provider_specified() throws Exception {
    def script = loadScript(scriptName)
    // when running with artifactCachingProxyEnabled set to true and an unavailable provider is specified
    env.ARTIFACT_CACHING_PROXY_PROVIDER = anotherArtifactCachingProxyProvider
    env.ARTIFACT_CACHING_PROXY_AVAILABLE_PROVIDERS = 'aws,azure' // without anotherArtifactCachingProxyProvider
    script.call(['artifactCachingProxyEnabled': true])
    printCallStack()
    // then it notices invalid or unavailable artifact caching provider has been specified and that it will fallback to repo.jenkins-ci.org
    assertTrue(assertMethodCallContainsPattern('echo', "WARNING: invalid or unavailable artifact caching proxy provider '${anotherArtifactCachingProxyProvider}' specified, will use repo.jenkins-ci.org"))
    // then there is no call to configFile containing the specified artifact caching proxy provider id
    assertFalse(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${anotherArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_artifact_caching_proxy_enabled_and_reachable_provider_specified() throws Exception {
    def script = loadScript(scriptName)
    // when running with artifactCachingProxyEnabled set to true and a reachable provider is specified
    env.ARTIFACT_CACHING_PROXY_PROVIDER = anotherArtifactCachingProxyProvider
    script.call(['artifactCachingProxyEnabled': true])
    printCallStack()
    // then an healthcheck is performed on the provider
    assertTrue(assertMethodCallContainsPattern('sh', healthCheckScriptSh) || assertMethodCallContainsPattern('bat', healthCheckScriptBat))
    // then it notices the provider isn't reachable and that it will fallback to repo.jenkins-ci.org
    assertFalse(assertMethodCallContainsPattern('echo', "WARNING: the artifact caching proxy from '${anotherArtifactCachingProxyProvider}' provider isn't reachable, will use repo.jenkins-ci.org"))
    // then there is no call to configFile containing the specified artifact caching proxy provider id
    assertTrue(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${anotherArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_artifact_caching_proxy_enabled_and_unreachable_provider_specified() throws Exception {
    def script = loadScript(scriptName)
    // Mock an healthcheck fail
    helper.addShMock(healthCheckScriptSh, '', 1)
    helper.addBatMock(healthCheckScriptBat, '', 1)

    // when running with artifactCachingProxyEnabled set to true and an unreachable provider is specified
    env.ARTIFACT_CACHING_PROXY_PROVIDER = anotherArtifactCachingProxyProvider
    script.call(['artifactCachingProxyEnabled': true])
    printCallStack()
    // then an healthcheck is performed on the provider
    assertTrue(assertMethodCallContainsPattern('sh', healthCheckScriptSh) || assertMethodCallContainsPattern('bat', healthCheckScriptBat))
    // then it notices the provider isn't reachable and that it will fallback to repo.jenkins-ci.org
    assertTrue(assertMethodCallContainsPattern('echo', "WARNING: the artifact caching proxy from '${anotherArtifactCachingProxyProvider}' provider isn't reachable, will use repo.jenkins-ci.org"))
    // then there is no call to configFile containing the specified artifact caching proxy provider id
    assertFalse(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${anotherArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_skip_artifact_caching_proxy_label_applied_to_pull_request() throws Exception {
    def script = loadScript(scriptName)

    // when running with artifactCachingProxyEnabled set to true, on a pull request with a "skip-artifact-caching-proxy" label
    env.BRANCH_IS_PRIMARY = false
    env.CHANGE_URL = changeUrlWithSkipACPLabel
    // Mock a "skip-artifact-caching-proxy" label
    helper.addShMock(prLabelsContainSkipACPScriptSh, '', 0)
    helper.addBatMock(prLabelsContainSkipACPScriptBat, '', 0)
    script.call(['artifactCachingProxyEnabled': true])
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
  void test_buildPlugin_without_skip_artifact_caching_proxy_label_applied_to_pull_request() throws Exception {
    def script = loadScript(scriptName)

    // when running with artifactCachingProxyEnabled set to true, on a pull request without a "skip-artifact-caching-proxy" label
    env.BRANCH_IS_PRIMARY = false
    env.CHANGE_URL = 'https://api.github.com/repos/jenkins-infra/pipeline-library/pull/123'
    // Mock the absence of "skip-artifact-caching-proxy" label
    helper.addShMock(prLabelsContainSkipACPScriptSh, '', 1)
    helper.addBatMock(prLabelsContainSkipACPScriptBat, '', 1)
    script.call(['artifactCachingProxyEnabled': true])
    printCallStack()
    // then a check is performed on the pull request labels
    assertTrue(assertMethodCallContainsPattern('sh', prLabelsContainSkipACPScriptSh) || assertMethodCallContainsPattern('bat', prLabelsContainSkipACPScriptBat))
    // then it doesn't notice the skipping of artifact-caching-proxy
    assertFalse(assertMethodCallContainsPattern('echo', "INFO: the label 'skip-artifact-caching-proxy' has been applied to the pull request, will use repo.jenkins-ci.org"))
    // then there is a call to configFile containing the default artifact caching proxy provider id
    assertTrue(assertMethodCallContainsPattern('configFile', "artifact-caching-proxy-${defaultArtifactCachingProxyProvider}"))
    // then it succeeds
    assertJobStatusSuccess()
  }
}
