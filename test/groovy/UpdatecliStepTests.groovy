

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

   // --- New Test Cases for Custom Version Functionality ---

  // Test that when a custom version is specified, the pipeline includes the download steps.
  @Test
  void itRunSuccessfullyWithCustomVersion() throws Exception {
    // Simulate system calls:
    helper.registerAllowedMethod('sh', [Map.class], { Map args ->
      if (args.script.contains("uname -m")) {
        return "x86_64\n"  // simulate CPU detection
      } else if (args.script.contains("curl -fL")) {
        return 0  // simulate a successful download
      } else if (args.script.contains("chmod +x updatecli")) {
        return 0
      } else if (args.script.contains("./updatecli version") || args.script.contains("updatecli.exe version")) {
        return 0
      }
      return 0
    })

    def script = loadScript(scriptName)
    script.call(version: '0.92.0')
    printCallStack()
    assertJobStatusSuccess()
    // Verify that the download stage is triggered: echo should mention "Downloading updatecli version 0.92.0 from"
    assertTrue(assertMethodCallContainsPattern('echo', 'Downloading updatecli version 0.92.0 from'))
    // Verify that the pipeline attempted to download via curl
    assertTrue(assertMethodCallContainsPattern('sh', 'curl -fL'))
    // Verify that after download the command uses the downloaded binary (indicated by "./updatecli diff")
    assertTrue(assertMethodCallContainsPattern('sh', './updatecli diff'))
  }

  // Test that when no version is specified, there is no call to download updatecli.
  @Test
  void itDoesNotDownloadUpdatecliWhenNoCustomVersionSpecified() throws Exception {
    def script = loadScript(scriptName)
    script.call() // no version attribute provided
    printCallStack()
    assertJobStatusSuccess()
    // There should be no echo message about downloading updatecli
    assertFalse(assertMethodCallContainsPattern('echo', 'Downloading updatecli version'))
    // And no shell step attempting to download via curl
    assertFalse(assertMethodCallContainsPattern('sh', 'curl -fL'))
  }

  // Test that if the custom version download fails, the pipeline errors immediately.
  @Test
  void itFailsWhenCustomVersionNotFound() throws Exception {
    helper.registerAllowedMethod('sh', [Map.class], { Map args ->
      if (args.script.contains("uname -m")) {
        return "x86_64\n"
      } else if (args.script.contains("curl -fL")) {
        return 1  // simulate download failure
      }
      return 0
    })

    def script = loadScript(scriptName)
    try {
      script.call(version: '0.99.99')
      fail("Expected error due to custom version not found")
    } catch (Exception e) {
      assertTrue(e.message.contains("Specified updatecli version 0.99.99 not found"))
    }
  }
}
