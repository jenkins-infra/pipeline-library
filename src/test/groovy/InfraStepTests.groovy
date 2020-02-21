import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import static com.lesfurets.jenkins.unit.MethodCall.callArgsToString
import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertFalse

class InfraStepTests extends BasePipelineTest {
  static final String scriptName = "vars/infra.groovy"
  Map env = [:]

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    binding.setVariable('env', env)
    binding.setProperty('scm', [:])
    binding.setProperty('mvnSettingsFile', 'settings.xml')

    helper.registerAllowedMethod('checkout', [Map.class], { 'OK' })
    helper.registerAllowedMethod('configFile', [Map.class], { 'OK' })
    helper.registerAllowedMethod('configFileProvider', [List.class, Closure.class], { list, closure ->
      def res = closure.call()
      return res
    })
    helper.registerAllowedMethod('echo', [String.class], { s -> s })
    helper.registerAllowedMethod('error', [String.class], {s ->
      updateBuildStatus('FAILURE')
      throw new Exception(s)
    })
    helper.registerAllowedMethod('git', [String.class], { 'OK' })
    helper.registerAllowedMethod("isUnix", [], { true })
    helper.registerAllowedMethod('sh', [String.class], { s -> s })
    helper.registerAllowedMethod('withCredentials', [List.class, Closure.class], { list, closure ->
      def res = closure.call()
      return res
    })
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
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'echo'
    }.any { call ->
      callArgsToString(call).contains('Cannot use Docker credentials outside of jenkins infra environment')
    })
    assertJobStatusSuccess()
  }

  @Test
  @Ignore("Some stackoverflow issues")
  void testCheckoutWithEnvVariable() throws Exception {
    def script = loadScript(scriptName)
    env.BRANCH_NAME = 'BRANCH'
    script.checkout()
    printCallStack()
    assertJobStatusSuccess()
  }

  @Test
  void testCheckoutWithArgument() throws Exception {
    def script = loadScript(scriptName)
    script.checkout('foo.git')
    printCallStack()
    assertJobStatusSuccess()
  }

  @Test
  void testCheckoutWithoutArgument() throws Exception {
    def script = loadScript(scriptName)
    // No env variable
    env.remove('BRANCH_NAME')
    try {
      script.checkout()
    } catch(e){
      //NOOP
    }
    printCallStack()
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'error'
    }.any { call ->
      callArgsToString(call).contains('buildPlugin must be used as part of a Multibranch Pipeline')
    })
    assertJobStatusFailure()
  }

  @Test
  void testRetrieveMavenSettingsFileWithEnvVariable() throws Exception {
    def script = loadScript(scriptName)
    env.MAVEN_SETTINGS_FILE_ID = 'foo.id'
    def result = script.retrieveMavenSettingsFile('foo.xml')
    assertTrue(result)
    printCallStack()
    assertJobStatusSuccess()
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'sh'
    }.any { call ->
      callArgsToString(call).contains('settings.xml foo.xml')
    })
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'configFile'
    }.any { call ->
      callArgsToString(call).contains('foo.id')
    })
  }

}
