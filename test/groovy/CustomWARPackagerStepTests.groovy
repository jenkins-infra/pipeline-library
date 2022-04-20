import org.junit.Before
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import static org.junit.Assert.assertTrue

class CustomWARPackagerStepTests extends BaseTest {
  static final String scriptName = 'vars/customWARPackager.groovy'

  static final String without_packaging_metadata = '''
  bar: true
  '''

  static final String without_packaging_config_metadata = '''
  packaging:
    bom: true
    environment: true
    jdk: 7
    cwpVersion: "1.2.3"
    archiveArtifacts: false
    installArtifacts: false
  '''

  static final String without_bom_config_metadata = '''
  packaging:
    config: true
    environment: true
    jdk: 7
    cwpVersion: "1.2.3"
    archiveArtifacts: false
    installArtifacts: false
  metadata:
    config: true
  '''

  static final String default_config_metadata = '''
  packaging:
    config: true
    environment: true
    jdk: 7
    cwpVersion: "1.2.3"
    archiveArtifacts: false
    installArtifacts: false
  metadata:
    labels:
      version: "foo"
      artifactId: "barId"
  '''

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    helper.registerAllowedMethod('findFiles', [Map.class], { String[] files = ['bom.yml', 'd1/bom.yml'] })
    helper.registerAllowedMethod('pwd', [], { '/foo' })
    helper.registerAllowedMethod('pwd', [Map.class], { '/bar' })
    helper.registerAllowedMethod('readYaml', [Map.class], {
      Yaml yaml = new Yaml()
      return yaml.load(default_config_metadata)
    })
  }

  @Test
  void test_without_packaging() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('readYaml', [Map.class], {
      Yaml yaml = new Yaml()
      return yaml.load(without_packaging_metadata)
    })
    // when running without metadata.packaging
    try {
      script.build('metadataFile', 'outputWAR', 'outputBOM', 'settings')
    } catch(e) {
      //NOOP
    }
    printCallStack()
    // then an error is thrown
    assertTrue(assertMethodCallContainsPattern('error', "No 'packaging' section in the metadata file metadataFile"))
    assertJobStatusFailure()
  }

  @Test
  void test_without_packaging_config() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('readYaml', [Map.class], {
      Yaml yaml = new Yaml()
      return yaml.load(without_packaging_config_metadata)
    })
    // when running without metadata.packaging
    try {
      script.build('metadataFile', 'outputWAR', 'outputBOM', 'settings')
    } catch(e) {
      //NOOP
    }
    printCallStack()
    // then an error is thrown
    assertTrue(assertMethodCallContainsPattern('error', "packaging.config or packaging.configFile must be defined"))
    assertJobStatusFailure()
  }

  @Test
  void test_without_bom_config() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('readYaml', [Map.class], { m ->
      Yaml yaml = new Yaml()
      return yaml.load(without_bom_config_metadata)
    })
    script.build('metadataFile', 'outputWAR', 'outputBOM', 'settings')
    printCallStack()
    assertTrue(assertMethodCallContainsPattern('echo', "BOM file is not explicitly defined, but there is bom.yml in the root. Using it"))
    assertJobStatusSuccess()
  }

  @Test
  void test_default_config() throws Exception {
    def script = loadScript(scriptName)
    script.build('metadataFile', 'outputWAR', 'outputBOM', 'settings')
    printCallStack()

    // then the war file with the artifact id and label from the metadata is copied
    assertTrue(assertMethodCallContainsPattern('sh', 'cp barId-foo.war'))

    // then the yaml file with the artifact id and label from the metadata is copied
    assertTrue(assertMethodCallContainsPattern('sh', 'barId-foo.bom.yml'))
    assertJobStatusSuccess()
  }

}
