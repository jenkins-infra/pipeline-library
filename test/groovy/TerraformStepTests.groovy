import org.junit.Before
import org.junit.Test
import mock.CurrentBuild

import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

class PullRequestMock {
  def comment(String message) {
  }
}

class TerraformStepTests extends BaseTest {
  static final String scriptName = 'vars/terraform.groovy'
  static final String dummyBuildUrl = 'https://ci.jenkins.io/dummy/jobs/main/1/' // Trailing slash is mandatory

  // The
  ArrayList stagingCustomCreds = [
    [$class: 'UsernamePasswordMultiBinding', credentialsId: 'credential-1', passwordVariable: 'STAGING_PSW', usernameVariable: 'STAGING_USR'],
    [$class: 'StringBinding', credentialsId: 'credential-common', variable: 'COMMON_SECRET'],
  ]
  ArrayList productionCustomCreds = [
    [$class: 'UsernamePasswordMultiBinding', credentialsId: 'credential-2', passwordVariable: 'PRODUCTION_PSW', usernameVariable: 'PRODUCTION_USR'],
    [$class: 'StringBinding', credentialsId: 'credential-common', variable: 'COMMON_SECRET'],
  ]

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    // Default behavior is a build triggered by a timertrigger on the main branch (most common case)
    binding.setProperty('currentBuild', new CurrentBuild('SUCCESS', ['hudson.triggers.TimerTrigger']))
    addEnvVar('BRANCH_NAME', 'main')

    binding.setProperty('scm', ['GIT_URL': 'https://github.com/lesfurets/jenkins-unit-test.git'])
    helper.registerAllowedMethod('ansiColor', [String.class, Closure.class], { s, body ->body() })
    helper.registerAllowedMethod('checkout', [Map.class], { m -> m })

    binding.setVariable('pullRequest', new PullRequestMock())

    // Used by the publish checks
    addEnvVar('BUILD_URL', dummyBuildUrl)
  }

  @Test
  void itRunSuccessfullyWithDefaultTimeTrigger() throws Exception {
    def script = loadScript(scriptName)

    // When calling the shared library global function with defaults
    script.call()
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With all the stages called
    /** Staging **/
    assertTrue(assertMethodCallContainsPattern('stage', 'Staging'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Validate Terraform for Staging Environment'))
    assertTrue(assertMethodCallContainsPattern('sh', 'make --directory=.shared-tools/terraform/ validate'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Commons Test Terraform Project'))
    assertTrue(assertMethodCallContainsPattern('sh', 'make --directory=.shared-tools/terraform/ common-tests'))
    assertFalse(assertMethodCallContainsPattern('sh', 'make --directory=.shared-tools/terraform/ tests'))

    /** Production **/
    assertTrue(assertMethodCallContainsPattern('stage', 'Production'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Generate Terraform Plan'))
    assertTrue(assertMethodCallContainsPattern('sh', 'make --directory=.shared-tools/terraform/ plan'))
    assertTrue(assertMethodCallContainsPattern('archiveArtifacts', 'terraform-plan-for-humans.txt'))
    // Default is the principal branch: not a PR, no notification, no estimated costs report
    assertFalse(assertMethodCallContainsPattern('stage', 'Notify User on the PR'))
    assertFalse(assertMethodCallContainsPattern('publishChecks', dummyBuildUrl + 'artifact/terraform-plan-for-humans.txt'))
    assertFalse(assertMethodCallContainsPattern('stage', 'Report estimated costs'))
    // Despite being on the principal branch, default build trigger is "timer"
    assertFalse(assertMethodCallContainsPattern('stage', 'Waiting for User Input (Manual Approval)'))
    assertFalse(assertMethodCallContainsPattern('input', 'Should we apply these changes to production?'))
    assertFalse(assertMethodCallContainsPattern('stage', 'Shipping Changes'))
    assertFalse(assertMethodCallContainsPattern('sh', 'make --directory=.shared-tools/terraform/ deploy'))

    // With the code checkouted (1 for production, 1 for staging by default)
    assertTrue(assertMethodCallOccurrences('checkout',4))

    // With Terraform configured to run in automation trhough the environment
    assertTrue(assertMethodCallContainsPattern('withEnv','TF_IN_AUTOMATION=1'))
    assertTrue(assertMethodCallContainsPattern('withEnv','TF_INPUT=0'))
    assertTrue(assertMethodCallContainsPattern('withEnv','TF_CLI_ARGS_plan=-detailed-exitcode'))

    // And none of the "custom" secrets defined in this tests suite (to ensure a non false positive on other tests)
    assertFalse(assertMethodCallContainsPattern('withCredentials','STAGING_PSW'))
    assertFalse(assertMethodCallContainsPattern('withCredentials','PRODUCTION_USR'))
    assertFalse(assertMethodCallContainsPattern('withCredentials','COMMON_SECRET'))

    // And a daily cron trigger for the job
    assertTrue(assertMethodCallContainsPattern('cron', '@daily'))

    // And the correct pod templates defined
    assertTrue(assertMethodCallContainsPattern('containerTemplate', 'jenkinsciinfra/hashicorp-tools:')) // Not tag as it's managed by updatecli
    assertTrue(assertMethodCallOccurrences('containerTemplate', 2)) // Only 1 container per pod, but 2 pod spawn (staging and production)

    // xterm color enabled (easier to read Terraform plans)
    assertTrue(assertMethodCallContainsPattern('ansiColor', 'xterm'))
    assertTrue(assertMethodCallOccurrences('ansiColor', 2))

    // Default timeout of 1 hour for each parallel branch
    assertTrue(assertMethodCallContainsPattern('timeout', 'time=1, unit=HOURS'))
    assertTrue(assertMethodCallOccurrences('timeout', 2))

    // Default pipeline properties
    assertTrue(assertMethodCallContainsPattern('disableConcurrentBuilds', ''))
    assertTrue(assertMethodCallContainsPattern('logRotator', 'numToKeepStr=50'))
  }

  @Test
  void itRunSuccessfullyWithDefaultOnPR() throws Exception {
    def script = loadScript(scriptName)

    // When calling the shared library global function with defaults
    // on a change request (PR) from <fork_repo>/main -> <current_repo>/main
    addEnvVar('CHANGE_ID', '1234')
    script.call()
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With all the stages called
    /** Staging **/
    assertTrue(assertMethodCallContainsPattern('stage', 'Staging'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Validate Terraform for Staging Environment'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Test Terraform Project'))

    /** Production **/
    assertTrue(assertMethodCallContainsPattern('stage', 'Production'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Generate Terraform Plan'))
    assertTrue(assertMethodCallContainsPattern('sh', 'make --directory=.shared-tools/terraform/ plan'))
    assertTrue(assertMethodCallContainsPattern('archiveArtifacts', 'terraform-plan-for-humans.txt'))

    // It's a change request: we expect a user notify and an estimated costs report
    assertTrue(assertMethodCallContainsPattern('stage', 'Notify User on the PR'))
    assertTrue(assertMethodCallContainsPattern('publishChecks', dummyBuildUrl + 'artifact/terraform-plan-for-humans.txt'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Report estimated costs'))
    // No User Approval on change requests
    assertFalse(assertMethodCallContainsPattern('stage', 'Waiting for User Input (Manual Approval)'))
    assertFalse(assertMethodCallContainsPattern('input', 'Should we apply these changes to production?'))
    // No shipping on change requests
    assertFalse(assertMethodCallContainsPattern('stage', 'Shipping Changes'))
    assertFalse(assertMethodCallContainsPattern('sh', 'make --directory=.shared-tools/terraform/ deploy'))

    // No daily cron trigger for the PR jobs
    assertFalse(assertMethodCallContainsPattern('cron', '@daily'))
    // Only 5 builds per PR to keep
    assertTrue(assertMethodCallContainsPattern('logRotator', 'numToKeepStr=5'))
  }

  @Test
  void itRunSuccessfullyWithRepoScanOnFeatureBranch() throws Exception {
    def script = loadScript(scriptName)

    // When calling the shared library global function with defaults
    // on a feature branch (not  a change request) triggered by a periodic code scan
    binding.setProperty('currentBuild', new CurrentBuild('SUCCESS', ['hudson.triggers.PeriodicFolderTrigger']))
    addEnvVar('BRANCH_NAME', 'feat/terraform')
    script.call()
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With all the stages called
    /** Staging **/
    assertTrue(assertMethodCallContainsPattern('stage', 'Staging'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Validate Terraform for Staging Environment'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Test Terraform Project'))

    /** Production **/
    assertTrue(assertMethodCallContainsPattern('stage', 'Production'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Generate Terraform Plan'))
    assertTrue(assertMethodCallContainsPattern('sh', 'make --directory=.shared-tools/terraform/ plan'))
    assertTrue(assertMethodCallContainsPattern('archiveArtifacts', 'terraform-plan-for-humans.txt'))

    // Default is the principal branch: not a PR, no notification, no estimated costs report
    assertFalse(assertMethodCallContainsPattern('stage', 'Notify User on the PR'))
    assertFalse(assertMethodCallContainsPattern('publishChecks', dummyBuildUrl + 'artifact/terraform-plan-for-humans.txt'))
    assertFalse(assertMethodCallContainsPattern('stage', 'Report estimated costs'))
    // Despite being on the principal branch, default build trigger is "timer"
    assertFalse(assertMethodCallContainsPattern('stage', 'Waiting for User Input (Manual Approval)'))
    assertFalse(assertMethodCallContainsPattern('input', 'Should we apply these changes to production?'))
    assertFalse(assertMethodCallContainsPattern('stage', 'Shipping Changes'))
    assertFalse(assertMethodCallContainsPattern('sh', 'make --directory=.shared-tools/terraform/ deploy'))

    // Ensure that drift-detection is disabled
    assertFalse(assertMethodCallContainsPattern('withEnv','TF_CLI_ARGS_plan=-detailed-exitcode'))

    // And a daily cron trigger for the job
    assertFalse(assertMethodCallContainsPattern('cron', '@daily'))
  }

  @Test
  void itRunSuccessfullyWithManualBuildCause() throws Exception {
    def script = loadScript(scriptName)

    // When calling the shared library global function with defaults
    // with a user manually-trigger build on the main branch
    binding.setProperty('currentBuild', new CurrentBuild('SUCCESS', ['hudson.model.Cause$UserIdCause']))
    script.call()
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    /** Staging is not run when user manually triggers the build **/
    assertFalse(assertMethodCallContainsPattern('stage', 'Staging'))
    assertFalse(assertMethodCallContainsPattern('stage', 'Validate Terraform for Staging Environment'))
    assertFalse(assertMethodCallContainsPattern('stage', 'Test Terraform Project'))
    assertFalse(assertMethodCallContainsPattern('sh', 'make --directory=.shared-tools/terraform/ common-tests'))
    /** Production **/
    assertTrue(assertMethodCallContainsPattern('stage', 'Production'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Generate Terraform Plan'))
    assertTrue(assertMethodCallContainsPattern('archiveArtifacts', 'terraform-plan-for-humans.txt'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Shipping Changes'))
    assertTrue(assertMethodCallContainsPattern('sh', 'make --directory=.shared-tools/terraform/ deploy'))
    // Different stages than defaults'
    assertTrue(assertMethodCallContainsPattern('stage', 'Waiting for User Input (Manual Approval)'))
    assertTrue(assertMethodCallContainsPattern('input', 'Should we apply these changes to production?'))

    // Terraform is set up through environment to NOT detect configuration drift
    assertFalse(assertMethodCallContainsPattern('withEnv','TF_CLI_ARGS_plan=-detailed-exitcode'))
  }

  @Test
  void itRunSuccessfullyWithCustomParameters() throws Exception {
    def script = loadScript(scriptName)
    final String customImage = 'hashicorp/terraform-full:0.13.0'

    // When calling the shared library global function with custom parameters
    script.call(
        cronTriggerExpression: '@weekly',
        stagingCredentials: stagingCustomCreds,
        productionCredentials: productionCustomCreds,
        agentContainerImage: customImage,
        )
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With the custom secrets defined
    // And none of the "custom" secrets defined in this tests suiye (to ensure a non false positive on other tests)
    assertTrue(assertMethodCallContainsPattern('withCredentials','STAGING_PSW'))
    assertTrue(assertMethodCallContainsPattern('withCredentials','PRODUCTION_USR'))
    assertTrue(assertMethodCallContainsPattern('withCredentials','COMMON_SECRET'))

    // And the custom cron trigger
    assertTrue(assertMethodCallContainsPattern('cron', '@weekly'))

    // And the custom agent container template defined
    assertFalse(assertMethodCallContainsPattern('containerTemplate', 'jenkinsciinfra/terraform:'))
    assertTrue(assertMethodCallContainsPattern('containerTemplate', customImage))
    assertTrue(assertMethodCallOccurrences('containerTemplate', 2))
  }
}
