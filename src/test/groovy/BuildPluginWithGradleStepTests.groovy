import com.lesfurets.jenkins.unit.BasePipelineTest
import mock.CurrentBuild
import mock.Infra
import org.junit.Before
import org.junit.Test
import static com.lesfurets.jenkins.unit.MethodCall.callArgsToString
import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertFalse

class BuildPluginWithGradleStepTests extends BasePipelineTest {
  static final String scriptName = 'vars/buildPluginWithGradle.groovy'

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
    binding.setProperty('infra', new Infra())
    binding.setProperty('buildPlugin', loadScript('vars/buildPlugin.groovy'))

    helper.registerAllowedMethod('node', [String.class, Closure.class], { list, closure ->
      def res = closure.call()
      return res
    })
    helper.registerAllowedMethod('timeout', [String.class], { s -> s })
    helper.registerAllowedMethod('stage', [String.class], { s -> s })
    helper.registerAllowedMethod('archiveArtifacts', [Map.class], { true })
    helper.registerAllowedMethod('isUnix', [], { true })
    helper.registerAllowedMethod('parallel', [Map.class, Closure.class], { list, closure ->
      def res = closure.call()
      return res
    })
    helper.registerAllowedMethod('timeout', [Integer.class, Closure.class], { list, closure ->
      def res = closure.call()
      return res
    })
    helper.registerAllowedMethod('durabilityHint', [String.class], { s -> s })
    helper.registerAllowedMethod('pwd', [Map.class], { '/tmp' })
    helper.registerAllowedMethod('echo', [String.class], { s -> s })
    helper.registerAllowedMethod('error', [String.class], { s ->
      updateBuildStatus('FAILURE')
      throw new Exception(s)
    })
  }

  @Test
  void test_buildPluginWithGradle_with_defaults() throws Exception {
    def script = loadScript(scriptName)
    // when running without any parameters
    script.call([:])
    printCallStack()
    // then it runs in a linux node
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'node'
    }.any { call ->
      callArgsToString(call).contains('linux')
    })
    // then it runs in a windows node
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'node'
    }.any { call ->
      callArgsToString(call).contains('windows')
    })
    // then it runs the junit step by default
    assertTrue(helper.callStack.any { call ->
      call.methodName == 'junit'
    })
    // then it runs the junit step with the gradle test format
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'junit'
    }.any { call ->
      callArgsToString(call).contains('**/build/test-results/**/*.xml')
    })
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPluginWithGradle_with_timeout() throws Exception {
    def script = loadScript(scriptName)
    script.call(timeout: 300)
    printCallStack()
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'echo'
    }.any { call ->
      callArgsToString(call).contains('lowering to 180')
    })
    assertJobStatusSuccess()
  }

  @Test
  void test_buildPluginWithGradle_without_tests() throws Exception {
    def script = loadScript(scriptName)
    script.call(tests: [skip: true])
    printCallStack()
    // the junit step is disabled
    assertFalse(helper.callStack.any { call ->
      call.methodName == 'junit'
    })
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
    assertTrue(helper.callStack.any { call ->
      call.methodName == 'junit'
    })
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
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'error'
    }.any { call ->
      callArgsToString(call).contains('There were test failures')
    })
    assertJobStatusFailure()
  }
}
