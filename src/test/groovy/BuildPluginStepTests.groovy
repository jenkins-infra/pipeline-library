import com.lesfurets.jenkins.unit.BasePipelineTest
import mock.CurrentBuild
import mock.Infra
import org.junit.Before
import org.junit.Test
import static com.lesfurets.jenkins.unit.MethodCall.callArgsToString
import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertNotNull

class BuildPluginStepTests extends BasePipelineTest {
  static final String scriptName = 'vars/buildPlugin.groovy'
  Map env = [:]

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
    env.NODE_LABELS = 'docker'
    binding.setVariable('env', env)
    binding.setProperty('scm', new String())
    binding.setProperty('mvnSettingsFile', 'settings.xml')
    binding.setProperty('infra', new Infra())

    helper.registerAllowedMethod('node', [String.class, Closure.class], { list, closure ->
      def res = closure.call()
      return res
    })
    helper.registerAllowedMethod('timeout', [String.class], { s -> s })
    helper.registerAllowedMethod('stage', [String.class], { s -> s })
    helper.registerAllowedMethod('fileExists', [String.class], { s -> s })
    helper.registerAllowedMethod('readFile', [String.class], { s -> s })
    helper.registerAllowedMethod('checkstyle', [Map.class], { true })
    helper.registerAllowedMethod('fingerprint', [String.class], { s -> s })
    helper.registerAllowedMethod('archiveArtifacts', [Map.class], { true })
    helper.registerAllowedMethod('deleteDir', [], { true })
    helper.registerAllowedMethod('isUnix', [], { true })
    helper.registerAllowedMethod('hasDockerLabel', [], { true })
    helper.registerAllowedMethod('sh', [String.class], { s -> s })
    helper.registerAllowedMethod('parallel', [Map.class, Closure.class], { list, closure ->
      def res = closure.call()
      return res
    })
    helper.registerAllowedMethod('timeout', [Integer.class, Closure.class], { list, closure ->
      def res = closure.call()
      return res
    })
    helper.registerAllowedMethod('findbugs', [Map.class], { true })
    helper.registerAllowedMethod('durabilityHint', [String.class], { s -> s })
    helper.registerAllowedMethod('pwd', [Map.class], { '/tmp' })
    helper.registerAllowedMethod('echo', [String.class], { s -> s })
    helper.registerAllowedMethod('error', [String.class], { s ->
      updateBuildStatus('FAILURE')
      throw new Exception(s)
    })
  }

  @Test
  void test_recommendedConfigurations() throws Exception {
    def script = loadScript(scriptName)
    def configurations = script.recommendedConfigurations()
    printCallStack()
    assertFalse(configurations.isEmpty())
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
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'error'
    }.any { call ->
      callArgsToString(call).contains('can not be used')
    })
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
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'error'
    }.any { call ->
      callArgsToString(call).contains('Configuration field "platform" must be specified: [jdk:1.8]')
    })
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
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'error'
    }.any { call ->
      callArgsToString(call).contains('Configuration filed "jdk" must be specified: [platform:linux]')
    })
    assertJobStatusFailure()
  }

  @Test
  void test_getConfigurations_without_parameters() throws Exception {
    def script = loadScript(scriptName)
    def configurations = script.getConfigurations([:])

    def expected = [['platform': 'linux', 'jdk': 8, 'jenkins': null, 'javaLevel': null],
                    ['platform': 'windows', 'jdk': 8, 'jenkins': null, 'javaLevel': null]]
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
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'node'
    }.any { call ->
      callArgsToString(call).contains('linux')
    })
    // then it runs in a windows node
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'node'
    }.any { call ->
      callArgsToString(call).contains('windows')
    })
    // then the archive stage happens
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'stage'
    }.any { call ->
      callArgsToString(call).contains('Archive')
    })
    // then it runs the junit step by default
    assertTrue(helper.callStack.any { call ->
      call.methodName == 'junit'
    })
    // then it runs the junit step with the maven test format
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'junit'
    }.any { call ->
      callArgsToString(call).contains('**/target/surefire-reports/**/*.xml,**/target/failsafe-reports/**/*.xml')
    })
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_timeout() throws Exception {
    def script = loadScript(scriptName)
    script.call(timeout: 300)
    printCallStack()
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'echo'
    }.any { call ->
      callArgsToString(call).contains('lowering to 180')
    })
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_without_tests() throws Exception {
    def script = loadScript(scriptName)
    script.call(tests: [skip: true])
    printCallStack()
    // then the junit step is disabled
    assertFalse(helper.callStack.any { call ->
      call.methodName == 'junit'
    })
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPlugin_with_defaults_with_gradle() throws Exception {
    def script = loadScript(scriptName)
    // when running in a non maven project
    helper.registerAllowedMethod('fileExists', [String.class], { s -> return !s.equals('pom.xml') })
    script.call([:])
    printCallStack()
    // then it runs the junit step with the no maven test format
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'junit'
    }.any { call ->
      callArgsToString(call).contains('**/build/test-results/**/*.xml')
    })
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
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'error'
    }.any { call ->
      callArgsToString(call).contains('There were test failures')
    })
    assertJobStatusFailure()
  }

  @Test
  void test_buildPlugin_with_findbugs_archive() throws Exception {
    def script = loadScript(scriptName)
    script.call(findbugs: [archive: true])
    printCallStack()
    // then it runs the findbugs
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'findbugs'
    }.any { call ->
      callArgsToString(call).contains('pattern=**/target/findbugsXml.xml')
    })
  }

  @Test
  void test_buildPlugin_with_checkstyle_archive() throws Exception {
    def script = loadScript(scriptName)
    script.call(checkstyle: [archive: true])
    printCallStack()
    // then it runs the findbugs
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'checkstyle'
    }.any { call ->
      callArgsToString(call).contains('**/target/checkstyle-result.xml')
    })
  }

  @Test
  void test_buildPlugin_with_configurations_and_incrementals() throws Exception {
    def script = loadScript(scriptName)
    // when running with incrementals
    helper.registerAllowedMethod('fileExists', [String.class], { s -> return s.equals('.mvn/extensions.xml') })
    helper.registerAllowedMethod('readFile', [String.class], { return 'git-changelist-maven-extension' })
    // and no jenkins version
    script.call(configurations: [['platform': 'linux', 'jdk': 8, 'jenkins': null, 'javaLevel': null]])
    printCallStack()
    // then it runs the fingerprint
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'fingerprint'
    }.any { call ->
      callArgsToString(call).contains('**/*-rc*.*/*-rc*.*')
    })
  }

  @Test
  void test_buildPlugin_with_build_error_should_run_the_archive_stage() throws Exception {
    def script = loadScript(scriptName)
    binding.setProperty('infra', new Infra(false, true))
    try {
      script.call(tests: [skip: false])
    } catch(e) {
      //NOOP
    }
    printCallStack()
    // then the archive stage happens
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'stage'
    }.any { call ->
      callArgsToString(call).contains('Archive')
    })
    // and the junit step is enabled
    assertTrue(helper.callStack.any { call -> call.methodName == 'stage' })
    assertJobStatusFailure()
  }
}
