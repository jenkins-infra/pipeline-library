import org.junit.Before
import org.junit.Test
import mock.Infra
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

class PublishReportsStepTests extends BaseTest {
  static final String scriptName = 'vars/publishReports.groovy'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    binding.setProperty('infra', new Infra(trusted: true))
  }

  @Test
  void test_without_trusted_infra() throws Exception {
    def script = loadScript(scriptName)
    binding.setProperty('infra', new Infra(trusted: false))
    // when running with !infra.isTrusted()
    try {
      script.call(null)
    } catch(e) {
      //NOOP
    }
    printCallStack()
    // then an error is thrown
    assertTrue(assertMethodCallContainsPattern('error', 'Can only call publishReports from within infra.ci or trusted.ci environment'))
    assertJobStatusFailure()
  }

  @Test
  void test_with_trusted_and_empty_infra() throws Exception {
    def script = loadScript(scriptName)
    // when running with an empty list and infra.isTrusted()
    script.call([])
    printCallStack()
    // then sanity check is run
    assertTrue(assertMethodCallContainsPattern('sh','az version'))
    assertTrue(assertMethodCallContainsPattern('sh','azcopy --version'))
    assertJobStatusSuccess()
  }
}
