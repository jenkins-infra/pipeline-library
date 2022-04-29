import org.junit.Before
import org.junit.Ignore
import org.junit.Test

import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertEquals

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

  @Test
  void testStashJenkinsWarFailsOnIncorrectData() throws Exception {
    def script = loadScript(scriptName)
    try {
      script.stashJenkinsWar('sdfsdfsdfsdf')
    } catch (e) {
      // intentionally left blank
    }

    printCallStack()
    assertTrue(assertMethodCallContainsPattern('error', 'Not sure how to interpret'))
    assertJobStatusFailure()
  }

  @Test
  void testStashJenkinsWarByVersion() throws Exception {
    def version = '3.333.3'
    helper.registerAllowedMethod('runMaven', [List], {List<String> command -> println command})

    def script = loadScript(scriptName)

    script.stashJenkinsWar(version)
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('stash', 'jenkins.war'))
    assertJobStatusSuccess()
  }
}
