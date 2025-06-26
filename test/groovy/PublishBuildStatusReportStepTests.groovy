import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.TimeZone
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

class PublishBuildStatusReportStepTests extends BaseTest {
  static final String scriptName = 'vars/publishBuildStatusReport.groovy'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
    // Mock the .format() extension method on the Date class for predictable timestamps in logs
    Date.metaClass.format = { String format, TimeZone tz -> '2025-06-17T15:10:00Z' }
  }

  void mockPrincipalBranch() {
    addEnvVar('BRANCH_IS_PRIMARY', 'true')
  }

  @Test
  void it_succeeds_on_principal_branch() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    addEnvVar('WORKSPACE', '/home/jenkins/workspace/test-job')
    addEnvVar('JENKINS_URL', 'https://ci.jenkins.io/')
    addEnvVar('JOB_NAME', 'my-folder/my-job')
    addEnvVar('BUILD_NUMBER', '123')
    binding.getVariable('currentBuild').currentResult = 'SUCCESS'

    script.call()
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('libraryResource', 'generateAndWriteBuildStatusReport.sh'))
    assertTrue(assertMethodCallContainsPattern('writeFile', '.jenkins-scripts/exec_generate_report_'))
    assertTrue(assertMethodCallContainsPattern('sh', 'chmod +x '))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'ENV_CONTROLLER_HOSTNAME=ci.jenkins.io'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'ENV_JOB_NAME=my-folder/my-job'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'ENV_BUILD_NUMBER=123'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'ENV_BUILD_STATUS=SUCCESS'))
    assertTrue(assertMethodCallContainsPattern('sh', '.jenkins-scripts/exec_generate_report_'))

    assertFalse("publishReports should NOT have been called", assertMethodCall('publishReports'))

    String expectedLocalPath = '/home/jenkins/workspace/test-job/build_status_reports/ci.jenkins.io/my-folder/my-job/status.json'
    String expectedRemoteUrl = 'https://buildsreportsjenkinsio.file.core.windows.net/builds-reports-jenkins-io/build_status_reports/ci.jenkins.io/my-folder/my-job/status.json'

    assertTrue(assertMethodCallContainsPattern('withEnv', "AZCOPY_LOCAL_PATH=${expectedLocalPath}"))
    assertTrue(assertMethodCallContainsPattern('withEnv', "AZCOPY_DESTINATION_URL=${expectedRemoteUrl}"))
    assertTrue(assertMethodCallContainsPattern('sh', 'azcopy logout'))
    assertTrue(assertMethodCallContainsPattern('sh', 'azcopy login --identity'))
    assertTrue(assertMethodCallContainsPattern('sh', 'azcopy copy'))
  }

  @Test
  void it_uses_fallback_values_when_env_vars_missing() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    addEnvVar('WORKSPACE', '/home/jenkins/workspace/fallback-job')

    script.call()
    printCallStack()

    assertJobStatusSuccess()
    assertMethodCallContainsPattern('withEnv', 'ENV_CONTROLLER_HOSTNAME=unknown_controller')
    assertMethodCallContainsPattern('withEnv', 'ENV_JOB_NAME=unknown_job')
    assertMethodCallContainsPattern('withEnv', 'ENV_BUILD_NUMBER=unknown_build')
    assertMethodCallContainsPattern('withEnv', 'ENV_BUILD_STATUS=UNKNOWN')

    String expectedLocalPath = '/home/jenkins/workspace/fallback-job/build_status_reports/unknown_controller/unknown_job/status.json'
    assertTrue(assertMethodCallContainsPattern('withEnv', "AZCOPY_LOCAL_PATH=${expectedLocalPath}"))
    assertTrue(assertMethodCallContainsPattern('sh', 'azcopy copy'))
  }

  @Test
  void it_does_nothing_on_non_principal_branch() throws Exception {
    def script = loadScript(scriptName)
    // No env vars set

    script.call()
    printCallStack()

    assertJobStatusSuccess()
    assertMethodCallContainsPattern('echo', 'Not on a principal branch')
    assertFalse(assertMethodCall('libraryResource'))
    assertFalse(assertMethodCall('writeFile'))
    assertFalse(assertMethodCallContainsPattern('sh', 'azcopy'))
    assertFalse(assertMethodCall('publishReports'))
  }
}
