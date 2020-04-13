import mock.Infra
import org.junit.Before
import org.junit.Test
import static org.junit.Assert.assertTrue

class RunBenchmarksStepTests extends BaseTest {
  static final String scriptName = 'vars/runBenchmarks.groovy'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    binding.setProperty('infra', new Infra(trusted: true))
  }

  @Test
  void test_without_parameters() throws Exception {
    def script = loadScript(scriptName)
    // when running without parameters
    script.call()
    printCallStack()
    // then echo
    assertTrue(assertMethodCallContainsPattern('echo', 'No artifacts to archive'))
    // then run in the highmem node
    assertTrue(assertMethodCallContainsPattern('node', 'highmem'))
    assertJobStatusSuccess()
  }

  @Test
  void test_with_artifacts() throws Exception {
    def script = loadScript(scriptName)
    // when running with artifacts
    script.call('foo')
    printCallStack()
    // then archiveArtifacts
    assertTrue(assertMethodCallContainsPattern('archiveArtifacts', 'foo'))
    assertJobStatusSuccess()
  }
}
