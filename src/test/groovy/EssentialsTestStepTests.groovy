import mock.Infra
import org.yaml.snakeyaml.Yaml
import org.junit.Before
import org.junit.Test
import static com.lesfurets.jenkins.unit.MethodCall.callArgsToString
import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertFalse

class EssentialsTestStepTests extends BaseTest {
  static final String scriptName = 'vars/essentialsTest.groovy'

  static final String  essentials = '''
flow:
  ath:
    testPluginResolution:
      skipOnUnmetDependencies: true
ath:
  disabled: false
pct:
  disabled: false
  '''

  static final String  essentials_with_disabled_skipOnUnmetDependencies = '''
flow:
  ath:
    testPluginResolution:
      skipOnUnmetDependencies: false
ath:
  disabled: false
pct:
  disabled: false
  '''

  static final String essentials_with_disabled_ath_and_pct = '''
  flow:
    ath:
      testPluginResolution:
        skipOnUnmetDependencies: false
  ath:
    disabled: true
  pct:
    disabled: true
    '''

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()
  }

  @Test
  void test_default_parameters() throws Exception {
    def script = loadScript(scriptName)
    // when running with !infra.isTrusted()
    script.call()
    printCallStack()
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'node'
    }.any { call ->
      callArgsToString(call).contains('docker && highmem')
    })

    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'dir'
    }.any { call ->
      callArgsToString(call).contains('.,')
    })

    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'readYaml'
    }.any { call ->
      callArgsToString(call).contains('/foo/essentials.yml')
    })

    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'runATH'
    }.any { call ->
      callArgsToString(call).contains('jenkins=file:///bar/custom.war, metadataFile=/foo/essentials.yml')
    })

    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'runPCT'
    }.any { call ->
      callArgsToString(call).contains('jenkins=file:///bar/custom.war, metadataFile=/foo/essentials.yml')
    })
  }

  @Test
  void test_with_baseDir_parameters() throws Exception {
    def script = loadScript(scriptName)
    script.call(baseDir: '/another')
    printCallStack()

    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'dir'
    }.any { call ->
      callArgsToString(call).contains('/another')
    })
  }

  @Test
  void test_with_metadataFile_parameters() throws Exception {
    def script = loadScript(scriptName)
    script.call(metadataFile: 'myfile.yml')
    printCallStack()

    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'readYaml'
    }.any { call ->
      callArgsToString(call).contains('/foo/myfile.yml')
    })

    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'runATH'
    }.any { call ->
      callArgsToString(call).contains('metadataFile=/foo/myfile.yml')
    })

    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'runATH'
    }.any { call ->
      callArgsToString(call).contains('skipOnInvalid')
    })

    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'runPCT'
    }.any { call ->
      callArgsToString(call).contains('metadataFile=/foo/myfile.yml')
    })
  }

  @Test
  void test_with_labels_parameters() throws Exception {
    def script = loadScript(scriptName)
    script.call(labels: 'mylabel')
    printCallStack()
    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'node'
    }.any { call ->
      callArgsToString(call).contains('mylabel')
    })
  }

  @Test
  void test_default_parameters_with_disabled_skipOnUnmetDependencies() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('readYaml', [Map.class], {
      Yaml yaml = new Yaml()
      return yaml.load(essentials_with_disabled_skipOnUnmetDependencies)
    })
    script.call()
    printCallStack()

    assertTrue(helper.callStack.findAll { call ->
      call.methodName == 'runATH'
    }.any { call ->
      callArgsToString(call).contains('failOnInvalid')
    })
  }

  @Test
  void test_default_parameters_with_disabed_ath_and_pct() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('readYaml', [Map.class], {
      Yaml yaml = new Yaml()
      return yaml.load(essentials_with_disabled_ath_and_pct)
    })
    script.call()
    printCallStack()
    assertFalse(helper.callStack.any { call -> call.methodName == 'runATH' })
    assertFalse(helper.callStack.any { call -> call.methodName == 'runPCT' })
  }

}
