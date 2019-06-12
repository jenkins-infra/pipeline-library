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

    env.JENKINS_URL = 'https://ci.jenkins.io/'
    binding.setVariable('env', env)
    binding.setProperty('scm', new String())
    binding.setProperty('mvnSettingsFile', 'settings.xml')

    helper.registerAllowedMethod('checkout', [String.class], { 'OK' })
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
    println 'testIsRunningOnJenkinsInfra'
    def script = loadScript(scriptName)
    assertTrue(script.isRunningOnJenkinsInfra())
  }

  @Test
  void testIsTrusted() throws Exception {
    println 'testIsTrusted'

    def script = loadScript(scriptName)
    env.JENKINS_URL = 'https://trusted.ci.jenkins.io:1443/'
    binding.setVariable('env', env)
    assertTrue(script.isTrusted())
  }

  @Test
  void testWithDockerCredentials() throws Exception {
    println 'testWithDockerCredentials'
    def script = loadScript(scriptName)
    helper.registerAllowedMethod("isRunningOnJenkinsInfra", [ ], { true })
    helper.registerAllowedMethod("isTrusted", [ ], { true })

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
    println 'testWithDockerCredentialsOutsideInfra'
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('isRunningOnJenkinsInfra', [ ], { false })
    def isOK = false
    script.withDockerCredentials() {
      isOK = true
    }
    printCallStack()
    /*assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'echo'
    }.any { call ->
      callArgsToString(call).contains('Cannot use Docker credentials outside of jenkins infra environment')
    })*/
    assertJobStatusSuccess()
  }

  @Test
  @Ignore("Some stackoverflow issues")
  void testCheckoutWithEnvVariable() throws Exception {
    println 'testCheckoutWithEnvVariable'
    def script = loadScript(scriptName)
    env.BRANCH_NAME = 'BRANCH'
    script.checkout()
    printCallStack()
    assertJobStatusSuccess()
  }

  @Test
  void testCheckoutWithArgument() throws Exception {
    println 'testCheckoutWithArgument'
    def script = loadScript(scriptName)
    script.checkout('foo.git')
    printCallStack()
    assertJobStatusSuccess()
  }

  @Test
  void testCheckoutWithoutArgument() throws Exception {
    println 'testCheckoutWithoutArgument'
    def script = loadScript(scriptName)
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
    println 'retrieveMavenSettingsFile'
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
