import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertFalse

class InfraStepTests extends BaseTest {
  static final String scriptName = "vars/infra.groovy"

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
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
    try {
      script.checkout()
    } catch(e){
      //NOOP
    }
    printCallStack()
    assertTrue(assertMethodCallContainsPattern('error', 'buildPlugin must be used as part of a Multibranch Pipeline'))
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
    assertTrue(assertMethodCallContainsPattern('sh', 'settings.xml foo.xml'))
    assertTrue(assertMethodCallContainsPattern('configFile', 'foo.id'))
  }

}
