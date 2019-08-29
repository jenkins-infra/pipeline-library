import com.lesfurets.jenkins.unit.BasePipelineTest
import mock.Infra
import org.junit.Before
import org.junit.Test
import static com.lesfurets.jenkins.unit.MethodCall.callArgsToString
import static org.junit.Assert.assertTrue

class RunBenchmarksStepTests extends BasePipelineTest {
  static final String scriptName = 'vars/runBenchmarks.groovy'
  Map env = [:]

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    binding.setVariable('env', env)
    binding.setProperty('infra', new Infra(true))

    helper.registerAllowedMethod('archiveArtifacts', [Map.class], { m -> m })
    helper.registerAllowedMethod('echo', [String.class], { s -> s })
    helper.registerAllowedMethod('lock', [String.class, Closure.class], { s, body -> body() })
    helper.registerAllowedMethod('node', [String.class, Closure.class], { s, body -> body() })
    helper.registerAllowedMethod('sh', [String.class], { s -> s })
    helper.registerAllowedMethod('stage', [String.class, Closure.class], { s, body -> body() })
  }

  @Test
  void test_without_parameters() throws Exception {
    def script = loadScript(scriptName)
    // when running without parameters
    script.call()
    printCallStack()
    // then echo
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'echo'
    }.any { call ->
      callArgsToString(call).contains('No artifacts to archive')
    })
    // then run in the highmem node
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'node'
    }.any { call ->
      callArgsToString(call).contains('highmem')
    })
    assertJobStatusSuccess()
  }

  @Test
  void test_with_artifacts() throws Exception {
    def script = loadScript(scriptName)
    // when running with artifacts
    script.call('foo')
    printCallStack()
    // then archiveArtifacts
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'archiveArtifacts'
    }.any { call ->
      callArgsToString(call).contains('foo')
    })
    assertJobStatusSuccess()
  }
}
