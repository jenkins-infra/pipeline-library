import org.junit.Before
import org.junit.Test
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

class PublishBuildStatusReportStepTests extends BaseTest {
  static final String scriptName = 'vars/publishBuildStatusReport.groovy'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
  }

  void mockPrincipalBranch() {
    addEnvVar('BRANCH_IS_PRIMARY', 'true')
  }

  @Test
  void it_succeeds_on_principal_branch() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    addEnvVar('JENKINS_URL', 'https://infra.jenkins.io/')
    addEnvVar('JOB_NAME', 'my-folder/my-job')
    addEnvVar('BUILD_NUMBER', '123')
    binding.getVariable('currentBuild').currentResult = 'SUCCESS'

    script.call()
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('pwd', 'tmp=true'))
    assertTrue(assertMethodCallContainsPattern('libraryResource', 'io/jenkins/infra/pipeline/generateAndWriteBuildStatusReport.sh'))
    assertTrue(assertMethodCallContainsPattern('writeFile', 'generateAndWriteBuildStatusReport.sh'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'BUILD_STATUS=SUCCESS'))
    assertTrue(assertMethodCallContainsPattern('sh', 'bash'))
  }

  @Test
  void it_errors_on_missing_jenkins_url() throws Exception {
    def script = loadScript(scriptName)
    // No JENKINS_URL set

    try {
      script.call()
      assertFalse("Expected error() to be called", true)
    } catch (Exception e) {
      assertTrue("Expected error about JENKINS_URL", e.getMessage().contains("JENKINS_URL is not set or empty"))
    }

    printCallStack()
    assertFalse(assertMethodCall('pwd'))
    assertFalse(assertMethodCall('writeFile'))
    assertFalse(assertMethodCall('sh'))
  }

  @Test
  void it_does_nothing_on_pull_request_build() throws Exception {
    def script = loadScript(scriptName)
    addEnvVar('CHANGE_ID', '42')

    script.call()
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('echo', 'Not publishing any build status report from a pull request'))
    assertFalse(assertMethodCall('pwd'))
    assertFalse(assertMethodCall('writeFile'))
    assertFalse(assertMethodCall('withEnv'))
    assertFalse(assertMethodCall('sh'))
  }

  @Test
  void it_skips_on_ci_jenkins_io() throws Exception {
    def script = loadScript(scriptName)
    addEnvVar('JENKINS_URL', 'https://ci.jenkins.io/')

    script.call()
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('echo', '[WARNING] Build status report not supported on ci.jenkins.io, skipping'))
    assertFalse(assertMethodCall('pwd'))
    assertFalse(assertMethodCall('writeFile'))
    assertFalse(assertMethodCall('withEnv'))
    assertFalse(assertMethodCall('sh'))
  }
}
