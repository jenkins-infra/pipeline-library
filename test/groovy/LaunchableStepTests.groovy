import static org.junit.Assert.assertTrue

import org.junit.Before
import org.junit.Test

class LaunchableStepTests extends BaseTest {
  static final String scriptName = 'vars/launchable.groovy'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
  }

  @Test
  void test_launchable_install() throws Exception {
    def script = loadScript(scriptName)
    script.install()
    printCallStack()
    // then it installs Launchable
    assertTrue(assertMethodCallContainsPattern('sh', '''
        python3 -m venv launchable
        launchable/bin/pip --require-virtualenv --no-cache-dir install -U setuptools wheel
        launchable/bin/pip --require-virtualenv --no-cache-dir install launchable
        '''.stripIndent()))
    // swallowing any errors that might occur
    assertTrue(assertMethodCall('catchError'))
    assertJobStatusSuccess()
  }

  @Test
  void test_launchable_verify() throws Exception {
    def script = loadScript(scriptName)
    script.call("verify")
    printCallStack()
    // swallowing any errors that might occur
    assertTrue(assertMethodCall('catchError'))
    // then it runs "launchable verify"
    assertTrue(assertMethodCallContainsPattern('sh', 'launchable verify'))
    assertJobStatusSuccess()
  }

  @Test
  void test_launchable_escaping() throws Exception {
    def script = loadScript(scriptName)
    script.call("record tests --no-build maven './**/target/surefire-reports'")
    printCallStack()
    // swallowing any errors that might occur
    assertTrue(assertMethodCall('catchError'))
    // then it passes the arguments without escaping to the Launchable CLI
    assertTrue(assertMethodCallContainsPattern('sh', "record tests --no-build maven './**/target/surefire-reports'"))
    assertJobStatusSuccess()
  }
}
