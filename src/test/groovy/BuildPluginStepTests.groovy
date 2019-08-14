import com.lesfurets.jenkins.unit.BasePipelineTest
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

    helper.registerAllowedMethod('node', [String.class], { s -> s })
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
    helper.registerAllowedMethod('parallel', [Map.class], { true })

//infra.runMaven
//infra.runWithJava
//infra.maybePublishIncrementals()

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

}
