

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

    /**
  * New Test Cases for Custom Version Functionality
  */

  // Test that when a custom version is specified, the pipeline includes the download steps.
  @Test
  void itRunSuccessfullyWithCustomVersion() throws Exception {
    // Simulate system calls for the single sh block.
    helper.registerAllowedMethod('sh', [Map.class], { Map args ->
      if (args.script.contains("uname -m")) {
        return "x86_64\n"  // simulate CPU detection as "x86_64"
      } else if (args.script.contains("curl --silent --location --output") && args.script.contains("updatecli_Linux_x86_64.tar.gz")) {
        return 0  // simulate a successful tar file download
      } else if (args.script.contains("tar --extract --gzip --file=") &&
                args.script.contains("--directory=\"/tmp/custom_updatecli\"") &&
                args.script.contains("updatecli")) {
        return 0  // simulate successful extraction of the tar archive
      } else if (args.script.contains("rm -f") && args.script.contains("updatecli_Linux_x86_64.tar.gz")) {
        return 0  // simulate successful removal of the tar archive
      } else if (args.script.contains("echo \"Using updatecli version:")) {
        return 0  // simulate version check within the sh block
      } else if (args.script.contains("/tmp/custom_updatecli/updatecli diff")) {
        return 0  // simulate execution of the final updatecli command
      }
      return 0
    })

    def script = loadScript(scriptName)
    script.call(version: '0.92.0')
    printCallStack()
    assertJobStatusSuccess()
    // Verify that the download stage is triggered: echo should mention "Downloading updatecli version ${UPDATECLI_VERSION} from ${downloadUrl}"
    assertTrue(assertMethodCallContainsPattern('sh', 'echo "Downloading updatecli version ${UPDATECLI_VERSION} from ${downloadUrl}'))
    // Verify that the pipeline attempted to download using curl with the expected tar file name for x86_64.
    assertTrue(assertMethodCallContainsPattern('sh', 'curl --silent --location --output ${tarFileName} ${downloadUrl}'))
    // Verify that the pipeline extracted the tar file.
    assertTrue(assertMethodCallContainsPattern('sh', 'tar --extract --gzip --file="${tarFileName}" --directory="/tmp/custom_updatecli" updatecli'))
    // Verify that the final command uses the locally extracted binary.
    assertTrue(assertMethodCallContainsPattern('sh', '/tmp/custom_updatecli/updatecli diff'))
  }

  // Test that when no version is specified, there is no call to download updatecli.
  @Test
  void itDoesNotDownloadUpdatecliWhenNoCustomVersionSpecified() throws Exception {
    def script = loadScript(scriptName)
    script.call() // no version attribute provided
    printCallStack()
    assertJobStatusSuccess()
    // There should be no echo message about downloading updatecli.
    assertFalse(assertMethodCallContainsPattern('echo', 'Downloading updatecli version'))
    // And no shell step with curl for a tar file should be present.
    assertFalse(assertMethodCallContainsPattern('sh', 'curl --silent --location --output'))
    // And no extraction command using tar should be found.
    assertFalse(assertMethodCallContainsPattern('sh', 'tar --extract'))
  }

  // Test that if the custom version download fails, the pipeline errors immediately.
  @Test
  void itFailsWhenCustomVersionNotFound() throws Exception {
    helper.registerAllowedMethod('sh', [Map.class], { Map args ->
      if (args.script.contains("curl --silent --location --output") && args.script.contains("updatecli_Linux")) {
        return 1  // simulate download failure (non-zero exit status)
      }
      if (args.script.contains("uname -m")) {
        return "x86_64\n"
      }
      return 0
    })

    def script = loadScript(scriptName)
    try {
      script.call(version: '0.99.99')
      fail("Expected error due to custom version not found")
    } catch (Exception e) {
      assertTrue(assertMethodCallContainsPattern('sh', 'echo "Updatecli custom download failed"'))
    }
  }
}
