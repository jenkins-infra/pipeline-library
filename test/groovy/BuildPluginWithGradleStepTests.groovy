import mock.CurrentBuild
import mock.Infra
import org.junit.Before
import org.junit.Test
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

class BuildPluginWithGradleStepTests extends BaseTest {
  static final String scriptName = 'vars/buildPluginWithGradle.groovy'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
  }

  @Test
  void test_buildPluginWithGradle_with_defaults() throws Exception {
    def script = loadScript(scriptName)
    // when running without any parameters
    script.call([:])
    printCallStack()
    // then it runs in a linux node
    assertTrue(assertMethodCallContainsPattern('node', 'linux'))
    // then it runs in a windows node
    assertTrue(assertMethodCallContainsPattern('node', 'windows'))
    // then it runs the junit step by default
    assertTrue(assertMethodCall('junit'))
    // then it runs the junit step with the gradle test format
    assertTrue(assertMethodCallContainsPattern('junit', '**/build/test-results/**/*.xml'))
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPluginWithGradle_with_timeout() throws Exception {
    def script = loadScript(scriptName)
    script.call(timeout: 300)
    printCallStack()
    assertTrue(assertMethodCallContainsPattern('echo', 'lowering to 180'))
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPluginWithGradle_without_tests() throws Exception {
    def script = loadScript(scriptName)
    script.call(tests: [skip: true])
    printCallStack()
    // the junit step is disabled
    assertFalse(assertMethodCall('junit'))
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPluginWithGradle_with_build_error() throws Exception {
    def script = loadScript(scriptName)
    binding.setProperty('infra', new Infra(buildError: true))
    try {
      script.call([:])
    } catch (ignored) {
      // intentionally left empty
    }
    printCallStack()
    // it runs the junit step
    assertTrue(assertMethodCall('junit'))
    assertJobStatusFailure()
  }

  @Test
  void test_buildPluginWithGradle_with_failfast_and_unstable() throws Exception {
    def script = loadScript(scriptName)
    // when running with fail fast and it's UNSTABLE
    binding.setProperty('currentBuild', new CurrentBuild('UNSTABLE'))
    try {
      script.call(failFast: true)
    } catch(e) {
      //NOOP
    }
    printCallStack()
    // then throw an error
    assertTrue(assertMethodCallContainsPattern('error', 'There were test failures'))
    assertJobStatusFailure()
  }

  @Test
  void test_buildPluginWithGradle_with_incrementals() throws Exception {
    def mockInfra = new Infra()
    binding.setProperty('infra', mockInfra)
    def script = loadScript(scriptName)

    script.call(incrementals: true)

    assertTrue(assertMethodCallContainsPattern('fingerprint', '**/*-rc*.*/*-rc*.*'))
  }
}
