import org.junit.Before
import org.junit.Test
import mock.Infra
import static com.lesfurets.jenkins.unit.MethodCall.callArgsToString
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertTrue

class PublishReportsStepTests extends BaseTest {
  static final String scriptName = 'vars/publishReports.groovy'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
  }

  @Test
  void test_without_trusted_infra() throws Exception {
    def script = loadScript(scriptName)
    binding.setProperty('infra', new Infra(trusted: false))
    // when running with !infra.isTrusted()
    try {
      script.call(null)
    } catch(e){
      //NOOP
    }
    printCallStack()
    // then an error is thrown
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'error'
    }.any { call ->
      callArgsToString(call).contains('Can only call publishReports from within the trusted.ci environment')
    })
    assertJobStatusFailure()
  }

  @Test
  void test_with_trusted_and_empty_infra() throws Exception {
    def script = loadScript(scriptName)
    // when running with an empty list and infra.isTrusted()
    script.call([])
    printCallStack()
    // then hardcoded credentials is correct
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'string'
    }.any { call ->
      callArgsToString(call).contains('credentialsId=azure-reports-access-key')
    })
    // No execution
    assertTrue(helper.callStack.findAll { call -> call.methodName == 'sh' }.isEmpty())
    assertJobStatusSuccess()
  }

  @Test
  void test_with_trusted_and_html_infra() throws Exception {
    def script = loadScript(scriptName)
    // when running with a html filename
    script.call([ 'foo.html' ])
    printCallStack()
    // then timeout is default and filename manipulations is in place
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'sh'
    }.any { call ->
      callArgsToString(call).contains('--timeout=60 --file=foo.html --name=foo.html --content-type="text/html"')
    })
    // another filename manipulations is in place
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'sh'
    }.any { call ->
      callArgsToString(call).trim().replaceAll(" +", " ").contains('--source . --destination-path / --pattern foo.html --content-type="text/html"')
    })
    assertJobStatusSuccess()
  }

  @Test
  void test_with_trusted_and_full_path_css_infra() throws Exception {
    def script = loadScript(scriptName)
    // when running with a css full path filename
    script.call([ '/bar/foo.css' ])
    printCallStack()
    // then timeout is default and filename manipulations is in place
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'sh'
    }.any { call ->
      callArgsToString(call).contains('--timeout=60 --file=/bar/foo.css --name=/bar/foo.css --content-type="text/css"')
    })
    // another filename manipulations is in place
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'sh'
    }.any { call ->
      callArgsToString(call).trim().replaceAll(" +", " ").contains('--source /bar --destination-path /bar --pattern foo.css --content-type="text/css"')
    })
    assertJobStatusSuccess()
  }
}
