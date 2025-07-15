// test/groovy/PublishBuildStatusReportStepTests.groovy

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
    addEnvVar('JENKINS_URL', 'https://ci.jenkins.io/')
    addEnvVar('JOB_NAME', 'my-folder/my-job')
    addEnvVar('BUILD_NUMBER', '123')
    binding.getVariable('currentBuild').currentResult = 'SUCCESS'

    script.call()
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('pwd', 'tmp=true'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'BUILD_STATUS=SUCCESS'))
    assertTrue(assertMethodCallContainsPattern('sh', 'bash ${tempDir}/generateAndWriteBuildStatusReport.sh'))
  }

  @Test
  void it_errors_on_missing_jenkins_url() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    // No JENKINS_URL set

    try {
      script.call()
      assertFalse("Expected error() to be called", true)
    } catch (Exception e) {
      assertTrue("Expected error about JENKINS_URL", e.getMessage().contains("JENKINS_URL is not set or empty"))
    }

    printCallStack()
    assertFalse(assertMethodCall('pwd'))
    assertFalse(assertMethodCall('sh'))
  }

  @Test
  void it_does_nothing_on_non_principal_branch() throws Exception {
    def script = loadScript(scriptName)
    // No BRANCH_IS_PRIMARY set, so it should return early

    script.call()
    printCallStack()

    assertJobStatusSuccess()
    assertFalse(assertMethodCall('pwd'))
    assertFalse(assertMethodCall('withEnv'))
    assertFalse(assertMethodCall('sh'))
  }
}
