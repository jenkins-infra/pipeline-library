import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertFalse

import org.junit.Before
import org.junit.Test

class LaunchableStepTests extends BaseTest {
  static final String scriptName = 'vars/launchable.groovy'
  static final String checkAlreadyInstalledScriptSh = 'command -v launchable'
  static final String checkAlreadyInstalledScriptBat = 'launchable --version 2>NUL || echo "NOT_INSTALLED"'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
  }

  @Test
  void test_launchable_install_already_installed() throws Exception {
    def script = loadScript(scriptName)
    // when Launchable is already installed
    helper.addShMock(checkAlreadyInstalledScriptSh, '', 0)
    helper.addBatMock(checkAlreadyInstalledScriptBat, { aScript -> return [stdout: 'a_version', exitValue: 0] })

    script.install()
    printCallStack()
    // then it does not install Launchable
    assertFalse(assertMethodCallContainsPattern('sh', '''
        python3 -m venv launchable
        launchable/bin/pip --require-virtualenv --no-cache-dir install -U setuptools wheel
        launchable/bin/pip --require-virtualenv --no-cache-dir install launchable
        ln -s launchable/bin/launchable /urs/local/bin/launchable
        '''.stripIndent())
        ||
        assertMethodCallContainsPattern('bat', '''
        python.exe -m pip --no-cache-dir install --upgrade setuptools wheel pip
        python.exe -m pip --no-cache-dir install launchable
        '''.stripIndent())
    )

    // swallowing any errors that might occur
    assertTrue(assertMethodCall('catchError'))
    // then it notices that Launchable is already installed
    assertTrue(assertMethodCallContainsPattern('echo', 'DEPRECATION NOTICE: Launchable is already installed, no need to run "launchable.install"'))
    assertJobStatusSuccess()
  }

  @Test
  void test_launchable_install_not_already_installed() throws Exception {
    def script = loadScript(scriptName)
    // when Launchable is not already installed
    helper.addShMock(checkAlreadyInstalledScriptSh, '', 1)
    helper.addBatMock(checkAlreadyInstalledScriptBat, { aScript -> return [stdout: 'NOT_INSTALLED', exitValue: 0] })

    script.install()
    printCallStack()
    // then it installs Launchable
    assertTrue(assertMethodCallContainsPattern('sh', '''
        python3 -m venv launchable
        launchable/bin/pip --require-virtualenv --no-cache-dir install -U setuptools wheel
        launchable/bin/pip --require-virtualenv --no-cache-dir install launchable
        ln -s launchable/bin/launchable /urs/local/bin/launchable
        '''.stripIndent())
        ||
        assertMethodCallContainsPattern('bat', '''
        python.exe -m pip --no-cache-dir install --upgrade setuptools wheel pip
        python.exe -m pip --no-cache-dir install launchable
        '''.stripIndent())
    )

    // swallowing any errors that might occur
    assertTrue(assertMethodCall('catchError'))
    // then it does not notice that Launchable is already installed
    assertFalse(assertMethodCallContainsPattern('echo', 'DEPRECATION NOTICE: Launchable is already installed, no need to run "launchable.install"'))
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
