import org.junit.Before
import org.junit.Test

import mock.CurrentBuild

import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

class ParallelDockerUpdatecliStepTests extends BaseTest {
  static final String scriptName = 'vars/parallelDockerUpdatecli.groovy'
  static final String testImageName = 'myImage'
  static final String anotherMainBranchName = 'another'
  static final String anotherCronTriggerExpression = '@daily'
  static final String anotherContainerMemory = '345Mi' // different than the default value specified in ${scriptName}
  static final String anotherCredentialsId = 'another-github-token'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
    // Mocks of the shared pipelines we want to test
    helper.registerAllowedMethod('buildDockerAndPublishImage', [String.class, Map.class], { s, m -> s + m })
    helper.registerAllowedMethod('updatecli', [Map.class], { m -> m })
    helper.registerAllowedMethod('fileExists', [String.class], { true })

    // Default behavior is a build trigger by a timertrigger on the main branch (most frequent case)
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

    // And updatecli(action: 'diff') is called
    assertTrue(assertMethodCallContainsPattern('updatecli', 'action=diff'))

    // And updatecli(action: 'apply') is called only if we are on the primary branch
    assertTrue(assertMethodCallContainsPattern('updatecli', 'action=apply'))
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
  }


  @Test
  void itRunsSuccessfullyWithCustomParameters() throws Exception {
    def script = loadScript(scriptName)

    // when calling with the "parallelDockerUpdatecli" function with custom parameters
    // Note: imageName & rebuildImageOnPeriodicJob have already been tested in other tests
    addEnvVar('BRANCH_IS_PRIMARY', 'true')
    script.call(
      imageName: testImageName,
      mainBranch: anotherMainBranchName,
      cronTriggerExpression: anotherCronTriggerExpression,
      containerMemory: anotherContainerMemory,
      credentialsId: anotherCredentialsId
    )
    printCallStack()

    // Then we expect a successfull build
    assertJobStatusSuccess()

    // And the error message is not shown
    assertFalse(assertMethodCallContainsPattern('echo', 'ERROR: no imageName provided.'))

    // And the correct image name is passed to buildDockerAndPublishImage
    assertTrue(assertMethodCallContainsPattern('buildDockerAndPublishImage', testImageName))

    // And updatecli(action: 'diff') is called
    assertTrue(assertMethodCallContainsPattern('updatecli', 'action=diff'))

    // And the custom parameters are taken in account
    assertTrue(assertMethodCallContainsPattern('buildDockerAndPublishImage', 'mainBranch=' + anotherMainBranchName))
    assertTrue(assertMethodCallContainsPattern('string', anotherCredentialsId))
    assertTrue(assertMethodCallContainsPattern('updatecli', 'cronTriggerExpression=' + anotherCronTriggerExpression))
    assertTrue(assertMethodCallContainsPattern('updatecli', 'containerMemory=' + anotherContainerMemory))
  }
}
