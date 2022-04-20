import mock.Infra
import org.yaml.snakeyaml.Yaml
import org.junit.Before
import org.junit.Test
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

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

    helper.registerAllowedMethod('readYaml', [Map.class], {
      Yaml yaml = new Yaml()
      return yaml.load(essentials)
    })
  }

  @Test
  void test_default_parameters() throws Exception {
    def script = loadScript(scriptName)
    // when running with !infra.isTrusted()
    script.call()
    printCallStack()
    assertTrue(assertMethodCallContainsPattern('node', 'docker-highmem'))
    assertTrue(assertMethodCallContainsPattern('dir', '.,'))
    assertTrue(assertMethodCallContainsPattern('readYaml', '/foo/essentials.yml'))
    assertTrue(assertMethodCallContainsPattern('runATH', 'jenkins=file:///bar/custom.war, metadataFile=/foo/essentials.yml'))
    assertTrue(assertMethodCallContainsPattern('runPCT', 'jenkins=file:///bar/custom.war, metadataFile=/foo/essentials.yml'))
  }

  @Test
  void test_with_baseDir_parameters() throws Exception {
    def script = loadScript(scriptName)
    script.call(baseDir: '/another')
    printCallStack()
    assertTrue(assertMethodCallContainsPattern('dir', '/another'))
  }

  @Test
  void test_with_metadataFile_parameters() throws Exception {
    def script = loadScript(scriptName)
    script.call(metadataFile: 'myfile.yml')
    printCallStack()
    assertTrue(assertMethodCallContainsPattern('readYaml', '/foo/myfile.yml'))
    assertTrue(assertMethodCallContainsPattern('runATH', 'metadataFile=/foo/myfile.yml'))
    assertTrue(assertMethodCallContainsPattern('runATH', 'skipOnInvalid'))
    assertTrue(assertMethodCallContainsPattern('runPCT', 'metadataFile=/foo/myfile.yml'))
  }

  @Test
  void test_with_labels_parameters() throws Exception {
    def script = loadScript(scriptName)
    script.call(labels: 'mylabel')
    printCallStack()
    assertTrue(assertMethodCallContainsPattern('node', 'mylabel'))
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
    assertTrue(assertMethodCallContainsPattern('runATH', 'failOnInvalid'))
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
    assertFalse(assertMethodCall('runATH'))
    assertFalse(assertMethodCall('runPCT'))
  }
}
