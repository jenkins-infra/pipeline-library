

import org.junit.Before
import org.junit.Test

import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

class UpdatecliStepTests extends BaseTest {
  static final String scriptName = "vars/updatecli.groovy"
  static final String anotherCredentialsId = 'another-credentials-id'
  Map env = [:]

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    //Mock of updatecli folder existence
    helper.registerAllowedMethod('fileExists', [String.class], { true })
  }

  @Test
  void itRunSuccessfullyWithDefault() throws Exception {
    def script = loadScript(scriptName)

    // when calling the "updatecli" function with default configuration
    script.call()
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // And the correct pod agent used
    assertTrue(assertMethodCallContainsPattern('node', 'jnlp-linux-arm64'))

    // And the repository checkouted
    assertTrue(assertMethodCallContainsPattern('checkout', ''))

    // And only the diff command called with default values
    assertTrue(assertMethodCallContainsPattern('sh','updatecli diff --config ./updatecli/updatecli.d --values ./updatecli/values.yaml'))
    assertFalse(assertMethodCallContainsPattern('sh','updatecli apply'))
  }

  @Test
  void itRunSuccessfullyWithCustomAction() throws Exception {
    def script = loadScript(scriptName)

    // when calling the "updatecli" function with a custom action "eat"
    script.call(action: 'eat')
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // And the repository checkouted
    assertTrue(assertMethodCallContainsPattern('checkout',''))

    // And only the custom command called with default values
    assertFalse(assertMethodCallContainsPattern('sh','updatecli diff --config ./updatecli/updatecli.d --values ./updatecli/values.yaml'))
    assertTrue(assertMethodCallContainsPattern('sh','updatecli eat --config ./updatecli/updatecli.d --values ./updatecli/values.yaml'))
  }

  @Test
  void itRunSuccessfullyWithCustomConfigAndEmptyValues() throws Exception {
    def script = loadScript(scriptName)

    // when calling the "updatecli" function with a custom config and an empty values
    script.call(config: './ops/config.yml', values: '', containerMemory: '512Mi')
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // And the repository checkouted
    assertTrue(assertMethodCallContainsPattern('checkout',''))

    // And only the default command called with custom config and NO values
    assertTrue(assertMethodCallContainsPattern('sh','updatecli diff --config ./ops/config.yml'))
    assertFalse(assertMethodCallContainsPattern('sh','--values'))
  }

  @Test
  void itRunSuccessfullyWithEmptyConfigAndCustomValues() throws Exception {
    def script = loadScript(scriptName)

    // when calling the "updatecli" function with custom values and an empty config
    script.call(values: './values.yaml', config: '')
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()


    // And the repository checkouted
    assertTrue(assertMethodCallContainsPattern('checkout',''))

    // And only the default command called with custom config and NO values
    assertTrue(assertMethodCallContainsPattern('sh','updatecli diff --values ./values.yaml'))
    assertFalse(assertMethodCallContainsPattern('sh','--config'))
  }

  @Test
  void itUsesCustomImageFromCustomConfig() throws Exception {
    def script = loadScript(scriptName)

    // when calling the "updatecli" function with a custom Docker image
    script.call(updatecliAgentLabel: 'jnlp-linux-amd64')
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // And the repository checkouted
    assertTrue(assertMethodCallContainsPattern('checkout',''))

    // And the correct pod template defined
    assertTrue(assertMethodCallContainsPattern('node', 'jnlp-linux-amd64'))

    // And only the diff command called with default values
    assertTrue(assertMethodCallContainsPattern('sh','updatecli diff --config ./updatecli/updatecli.d --values ./updatecli/values.yaml'))
    assertFalse(assertMethodCallContainsPattern('sh','updatecli apply'))
  }

  @Test
  void itUsesCustomCredentialsId() throws Exception {
    def script = loadScript(scriptName)

    // when calling the "updatecli" function with a custom credentialsId
    script.call(credentialsId: anotherCredentialsId)
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // And the custom credentialsId is taken in account
    assertTrue(assertMethodCallContainsPattern('usernamePassword', "credentialsId=${anotherCredentialsId}"))
  }
}
