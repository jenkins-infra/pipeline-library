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
    assertTrue(assertMethodCallContainsPattern('error', 'Can only call publishReports from within the trusted.ci environment'))
    assertJobStatusFailure()
  }

  @Test
  void test_with_trusted_and_empty_infra() throws Exception {
    def script = loadScript(scriptName)
    // when running with an empty list and infra.isTrusted()
    script.call([])
    printCallStack()
    // then hardcoded credentials is correct
    assertTrue(assertMethodCallContainsPattern('string', 'credentialsId=azure-reports-access-key'))
    // No execution
    assertFalse(assertMethodCallContainsPattern('sh','az storage blob'))
    assertJobStatusSuccess()
  }

  @Test
  void test_with_trusted_and_html_infra() throws Exception {
    def script = loadScript(scriptName)
    def file = 'foo.html'
    // when running with a html filename
    script.call([file])
    printCallStack()
    // then timeout is default and filename manipulations is in place
    assertTrue(assertMethodCallContainsPattern('withEnv', 'TIMEOUT=60'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "FILENAME=${file}"))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'UPLOADFLAGS=--content-type="text/html"'))
    assertTrue(assertMethodCallContainsPattern('sh', 'az storage blob upload --account-name=prodjenkinsreports --container=reports --timeout=${TIMEOUT} --file=${FILENAME} --name=${FILENAME} ${UPLOADFLAGS} --overwrite'))
    // another filename manipulations is in place
    assertTrue(assertMethodCallContainsPattern('withEnv', 'SOURCE_DIRNAME=.'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'DESTINATION_PATH=/'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'PATTERN=foo.html'))
    assertTrue(assertMethodCallContainsPattern('sh', 'az storage file upload-batch --account-name prodjenkinsreports --destination reports --source ${SOURCE_DIRNAME} --destination-path ${DESTINATION_PATH} --pattern ${PATTERN} ${UPLOADFLAGS}'))
    assertJobStatusSuccess()
  }

  @Test
  void test_with_trusted_and_full_path_css_infra() throws Exception {
    def script = loadScript(scriptName)
    // when running with a css full path filename
    def file = '/bar/foo.css'
    script.call([file])
    printCallStack()
    // then timeout is default and filename manipulations is in place
    assertTrue(assertMethodCallContainsPattern('withEnv', 'TIMEOUT=60'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "FILENAME=${file}"))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'UPLOADFLAGS=--content-type="text/css"'))
    assertTrue(assertMethodCallContainsPattern('sh', 'az storage blob upload --account-name=prodjenkinsreports --container=reports --timeout=${TIMEOUT} --file=${FILENAME} --name=${FILENAME} ${UPLOADFLAGS} --overwrite'))
    // another filename manipulations is in place
    assertTrue(assertMethodCallContainsPattern('withEnv', 'SOURCE_DIRNAME=/bar'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'DESTINATION_PATH=/bar'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'PATTERN=foo.css'))
    assertTrue(assertMethodCallContainsPattern('sh', 'az storage file upload-batch --account-name prodjenkinsreports --destination reports --source ${SOURCE_DIRNAME} --destination-path ${DESTINATION_PATH} --pattern ${PATTERN} ${UPLOADFLAGS}'))
    assertJobStatusSuccess()
  }
}
