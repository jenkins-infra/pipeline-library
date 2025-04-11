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
    assertEquals(configurations.size(), 2)
    assertNotNull(configurations.find {it.platform.equals('bar')})
    assertNotNull(configurations.find {it.platform.equals('foo')})
    printCallStack()
    assertJobStatusSuccess()
  }

  @Test
  void test_getConfigurations_implicit_with_jenkinsVersions() throws Exception {
    def script = loadScript(scriptName)
    def configurations = script.getConfigurations(jenkinsVersions: ['1.x', '2.x'])
    assertEquals(configurations.size(), 4)
    assertNotNull(configurations.find{it.jenkins.equals('1.x')})
    assertNotNull(configurations.find{it.jenkins.equals('2.x')})
    printCallStack()
    assertJobStatusSuccess()
  }

  @Test
  void test_getConfigurations_implicit_with_jdkVersions() throws Exception {
    def script = loadScript(scriptName)
    def configurations = script.getConfigurations(jdkVersions: ['1.4', '1.3'])
    assertEquals(configurations.size(), 4)
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
    // then it runs a stage in a linux VM by default
    assertTrue(assertMethodCallContainsPattern('node', 'linux-8-false'))
    // then it runs a stage in a Windows VM by default
    assertTrue(assertMethodCallContainsPattern('node', 'windows-8-false'))
    // then it runs the junit step by default
    assertTrue(assertMethodCall('junit'))
    // then it runs the junit step with the maven test format
    assertTrue(assertMethodCallContainsPattern('junit', '**/target/surefire-reports/**/*.xml,**/target/failsafe-reports/**/*.xml'))
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_container_agents() throws Exception {
    def script = loadScript(scriptName)
    // when running with useContainerAgent set to true
    script.call([useContainerAgent: true])
    printCallStack()
    // then it runs a stage in a linux container by default
    assertTrue(assertMethodCallContainsPattern('node', 'linux-8-true'))
    // then it runs a stage in a Windows container by default
    assertTrue(assertMethodCallContainsPattern('node', 'windows-8-true'))
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_forkCount() throws Exception {
    def script = loadScript(scriptName)
    // Run with forkCount=1C
    script.call([forkCount: '1C'])
    printCallStack()
    // and confirm expected message is output
    assertTrue(assertMethodCallContainsPattern('echo', 'Running parallel tests with forkCount'))
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_without_forkCount() throws Exception {
    def script = loadScript(scriptName)
    // Run without forkCount
    script.call()
    printCallStack()
    // and confirm forkCount message is not output
    assertFalse(assertMethodCallContainsPattern('echo', 'Running parallel tests with forkCount'))
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_customplatforms() throws Exception {
    def script = loadScript(scriptName)
    // when running with useContainerAgent set to true
    script.call(platforms: ['openbsd', 'maven-windows-experimental'])
    printCallStack()
    // then it runs a stage in an openbsd node
    assertTrue(assertMethodCallContainsPattern('node', 'openbsd-8-false'))
    // then it runs a stage in a maven-windows-experimental node with a warning message
    assertTrue(assertMethodCallContainsPattern('node', 'maven-windows-experimental-8-false'))
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
  void test_buildPlugin_with_record_coverage_defaults() throws Exception {
    def script = loadScript(scriptName)
    script.call()
    printCallStack()

    assertTrue(assertMethodCall('recordCoverage'))
    assertDefaultRecordCoverageWithJaCoCo()
  }

  private assertDefaultRecordCoverageWithJaCoCo() {
    assertTrue(assertMethodCallContainsPattern('recordCoverage', '{tools=[{parser=JACOCO, pattern=**/jacoco/jacoco.xml}], sourceCodeRetention=MODIFIED}'))
  }

  @Test
  void test_buildPlugin_with_record_coverage_custom() throws Exception {
    def script = loadScript(scriptName)
    script.call(jacoco: [sourceCodeRetention: 'EVERY_BUILD', sourceDirectories: [[path: 'plugin/src/main/java']]])
    printCallStack()

    assertTrue(assertMethodCall('recordCoverage'))
    assertTrue(assertMethodCallContainsPattern('recordCoverage', '{tools=[{parser=JACOCO, pattern=**/jacoco/jacoco.xml}], sourceCodeRetention=EVERY_BUILD, sourceDirectories=[{path=plugin/src/main/java}]}'))
  }

  @Test
  void test_buildPlugin_with_record_coverage_pit_default() throws Exception {
    def script = loadScript(scriptName)
    script.call(pit: [skip: false])
    printCallStack()

    assertTrue(assertMethodCall('recordCoverage'))
    assertDefaultRecordCoverageWithJaCoCo()

    assertTrue(assertMethodCallContainsPattern('recordCoverage', '{tools=[{parser=PIT, pattern=**/pit-reports/mutations.xml}], id=pit, name=Mutation Coverage, sourceCodeRetention=MODIFIED}'))
  }

  @Test
  void test_buildPlugin_with_record_coverage_pit_custom() throws Exception {
    def script = loadScript(scriptName)
    script.call(pit: [sourceCodeRetention: 'EVERY_BUILD', name: 'PIT', sourceDirectories: [[path: 'plugin/src/main/java']]])
    printCallStack()

    assertTrue(assertMethodCall('recordCoverage'))
    assertDefaultRecordCoverageWithJaCoCo()

    assertTrue(assertMethodCallContainsPattern('recordCoverage', '{tools=[{parser=PIT, pattern=**/pit-reports/mutations.xml}], id=pit, name=PIT, sourceCodeRetention=EVERY_BUILD, sourceDirectories=[{path=plugin/src/main/java}]}'))
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
}
