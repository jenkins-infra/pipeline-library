import org.junit.Before
import org.junit.Test

import mock.CurrentBuild

import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

class ParallelDockerUpdatecliStepTests extends BaseTest {
  static final String scriptName = 'vars/parallelDockerUpdatecli.groovy'
  static final String testImageName = 'myImage'
  static final String anotherCronTriggerExpression = '@daily'
  static final String anotherContainerMemory = '345Mi' // different than the default value specified in ${scriptName}
  static final String defaultUpdatecliCredentialsId = 'github-app-updatecli-on-jenkins-infra'
  static final String defaultDockerGitCredentialsId = 'github-app-infra'
  static final String anotherCredentialsId = 'another-github-token'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
    // Mocks of the shared pipelines we want to test
    helper.registerAllowedMethod('buildDockerAndPublishImage', [String.class, Map.class], { s, m -> s + m })
    helper.registerAllowedMethod('updatecli', [Map.class], { m -> m })
    helper.registerAllowedMethod('fileExists', [String.class], { true })

    // Default behavior is a build trigger by a timertrigger on the principal branch (most frequent case)
    binding.setProperty('currentBuild', new CurrentBuild('SUCCESS', ['hudson.triggers.TimerTrigger']))
  }

  @Test
  void itFailsWithDefault() throws Exception {
    def script = loadScript(scriptName)

    // when calling with the "parallelDockerUpdatecli" function with default configuration
    script.call()
    printCallStack()

    // Then we expect a failing build
    assertJobStatusFailure()

    // And the error message is shown
    assertTrue(assertMethodCallContainsPattern('echo', 'ERROR: no imageName provided.'))

    // No Warning issued
    assertFalse(assertMethodCallContainsPattern('echo', 'WARNING:'))
  }

  @Test
  void itRunsSuccessfullyWithImageNameOnPrimaryBranch() throws Exception {
    def script = loadScript(scriptName)

    // when calling with the "parallelDockerUpdatecli" function with default configuration on the primary branch
    addEnvVar('BRANCH_IS_PRIMARY', 'true')
    script.call(imageName: testImageName)
    printCallStack()

    // Then we expect a successfull build
    assertJobStatusSuccess()

    // And the error message is not shown
    assertFalse(assertMethodCallContainsPattern('echo', 'ERROR: no imageName provided.'))

    // And the correct image name is passed to buildDockerAndPublishImage
    assertTrue(assertMethodCallContainsPattern('buildDockerAndPublishImage', testImageName))
    // And the correct settings
    assertTrue(assertMethodCallContainsPattern('buildDockerAndPublishImage', 'automaticSemanticVersioning=true'))
    assertTrue(assertMethodCallContainsPattern('buildDockerAndPublishImage', "gitCredentials=${defaultDockerGitCredentialsId}"))

    // And updatecli(action: 'diff') is called
    assertTrue(assertMethodCallContainsPattern('updatecli', 'action=diff'))

    // And updatecli(action: 'apply') is called only if we are on the primary branch
    assertTrue(assertMethodCallContainsPattern('updatecli', 'action=apply'))

    // No Warning issued
    assertFalse(assertMethodCallContainsPattern('echo', 'WARNING:'))
  }

  @Test
  void itRunsSuccessfullyWithImageNameNotOnPrimaryBranch() throws Exception {
    def script = loadScript(scriptName)

    // when calling with the "parallelDockerUpdatecli" function with default configuration not on the primary branch (env.BRANCH_IS_PRIMARY not set)
    script.call(imageName: testImageName)
    printCallStack()

    // Then we expect a successfull build
    assertJobStatusSuccess()

    // And the error message is not shown
    assertFalse(assertMethodCallContainsPattern('echo', 'ERROR: no imageName provided.'))

    // And the correct image name is passed to buildDockerAndPublishImage
    assertTrue(assertMethodCallContainsPattern('buildDockerAndPublishImage', testImageName))

    // And updatecli(action: 'diff') is called
    assertTrue(assertMethodCallContainsPattern('updatecli', 'action=diff'))

    // And updatecli(action: 'apply') is called only if we are on the primary branch
    assertFalse(assertMethodCallContainsPattern('updatecli', 'action=apply'))

    // No Warning issued
    assertFalse(assertMethodCallContainsPattern('echo', 'WARNING:'))
  }

  @Test
  void itRunsSuccessfullyWithImageNameWithoutRebuildImage() throws Exception {
    def script = loadScript(scriptName)

    // when calling with the "parallelDockerUpdatecli" function with rebuildImageOnPeriodicJob set to false
    script.call(imageName: testImageName, rebuildImageOnPeriodicJob: false)
    printCallStack()

    // Then we expect a successfull build
    assertJobStatusSuccess()

    // And the error message is not shown
    assertFalse(assertMethodCallContainsPattern('echo', 'ERROR: no imageName provided.'))

    // And buildDockerAndPublishImage is not called (default behavior set as a build trigger by a TimerTrigger)
    assertFalse(assertMethodCallContainsPattern('buildDockerAndPublishImage', testImageName))

    // And updatecli(action: 'diff') is called
    assertTrue(assertMethodCallContainsPattern('updatecli', 'action=diff'))

    // No Warning issued
    assertFalse(assertMethodCallContainsPattern('echo', 'WARNING:'))
  }

  @Test
  void itRunsSuccessfullyWithImageNameWithoutRebuildImageNorTimerTrigger() throws Exception {
    def script = loadScript(scriptName)

    // when calling with the "parallelDockerUpdatecli" function with rebuildImageOnPeriodicJob set to false, and the trigger is not a TimerTrigger
    binding.setProperty('currentBuild', new CurrentBuild('SUCCESS', ['hudson.triggers.NotATimerTrigger']))
    script.call(imageName: testImageName, rebuildImageOnPeriodicJob: false)
    printCallStack()

    // Then we expect a successfull build
    assertJobStatusSuccess()

    // And the error message is not shown
    assertFalse(assertMethodCallContainsPattern('echo', 'ERROR: no imageName provided.'))

    // And buildDockerAndPublishImage is called (default behavior set as a build trigger which is not a timertrigger)
    assertTrue(assertMethodCallContainsPattern('buildDockerAndPublishImage', testImageName))

    // And updatecli(action: 'diff') is called
    assertTrue(assertMethodCallContainsPattern('updatecli', 'action=diff'))

    // No Warning issued
    assertFalse(assertMethodCallContainsPattern('echo', 'WARNING:'))
  }

  @Test
  void itRunsSuccessfullyWithCustomParameters() throws Exception {
    def script = loadScript(scriptName)

    // When the "parallelDockerUpdatecli" function is called with custom parameters
    // Note: imageName & rebuildImageOnPeriodicJob have already been tested in other tests
    addEnvVar('BRANCH_IS_PRIMARY', 'true')
    script.call(
        imageName: testImageName,
        updatecliApplyCronTriggerExpression: anotherCronTriggerExpression,
        updatecliConfig: [
          containerMemory: anotherContainerMemory,
        ],
        buildDockerConfig: [
          includeImageNameInTag: true,
        ],
        updatecliCredentialsId: anotherCredentialsId
        )
    printCallStack()

    // Then we expect a successfull build
    assertJobStatusSuccess()

    // And the error message is not shown
    assertFalse(assertMethodCallContainsPattern('echo', 'ERROR: no imageName provided.'))

    // And the correct image name is passed to buildDockerAndPublishImage
    assertTrue(assertMethodCallContainsPattern('buildDockerAndPublishImage', testImageName))
    // And the correct fixed settings for buildDockerAndPublishImage
    assertTrue(assertMethodCallContainsPattern('buildDockerAndPublishImage', 'automaticSemanticVersioning=true'))
    assertTrue(assertMethodCallContainsPattern('buildDockerAndPublishImage', "gitCredentials=${defaultDockerGitCredentialsId}"))
    // And the custom settings for buildDockerAndPublishImage
    assertTrue(assertMethodCallContainsPattern('buildDockerAndPublishImage', 'includeImageNameInTag=true'))

    // And updatecli(action: 'diff') is called
    assertTrue(assertMethodCallContainsPattern('updatecli', 'action=diff'))

    // No Warning issued
    assertFalse(assertMethodCallContainsPattern('echo', 'WARNING:'))

    // And the custom parameters are taken in account for docker image build
    assertTrue(assertMethodCallContainsPattern('buildDockerAndPublishImage', "gitCredentials=${defaultDockerGitCredentialsId}"))
    assertTrue(assertMethodCallContainsPattern('updatecli', "credentialsId=${anotherCredentialsId}"))

    // And the method "updatecli()" is called for "diff" and "apply" actions (both with the same custom parameters)
    assertTrue(assertMethodCallOccurrences('updatecli', 2))
    assertTrue(assertMethodCallContainsPattern('updatecli', 'action=diff'))
    assertTrue(assertMethodCallContainsPattern('updatecli', 'action=apply'))
    assertTrue(assertMethodCallContainsPattern('updatecli', 'cronTriggerExpression=' + anotherCronTriggerExpression))
    assertTrue(assertMethodCallContainsPattern('updatecli', 'containerMemory=' + anotherContainerMemory))
  }

  @Test
  void itRunsSuccessfullyWithOutdatedUpdatecliConfig() throws Exception {
    def script = loadScript(scriptName)

    // When the "parallelDockerUpdatecli" function is called with custom parameters, including legcay parameters for updatecli
    addEnvVar('BRANCH_IS_PRIMARY', 'true')
    script.call(
        imageName: testImageName,
        containerMemory: anotherContainerMemory,
        updatecliApplyCronTriggerExpression: anotherCronTriggerExpression,
        )
    printCallStack()

    // Then a successfull build is expected
    assertJobStatusSuccess()

    // And the error message is not shown
    assertFalse(assertMethodCallContainsPattern('echo', 'ERROR: no imageName provided.'))

    // And the correct image name is passed to buildDockerAndPublishImage
    assertTrue(assertMethodCallContainsPattern('buildDockerAndPublishImage', testImageName))

    // And a warning message is shown (updatecli legacy config is used)
    assertTrue(assertMethodCallContainsPattern('echo', 'WARNING: passing the attribute'))

    // And the method "updatecli()" is called for "diff" and "apply" actions (both with the same custom config)
    assertTrue(assertMethodCallOccurrences('updatecli', 2))
    assertTrue(assertMethodCallContainsPattern('updatecli', 'action=diff'))
    assertTrue(assertMethodCallContainsPattern('updatecli', 'action=apply'))
    assertTrue(assertMethodCallContainsPattern('updatecli', 'cronTriggerExpression=' + anotherCronTriggerExpression))
    assertTrue(assertMethodCallContainsPattern('updatecli', 'containerMemory=' + anotherContainerMemory))
  }
}
