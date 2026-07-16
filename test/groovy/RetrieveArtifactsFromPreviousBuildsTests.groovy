import org.junit.Before
import org.junit.Test

class RetrieveArtifactsFromPreviousBuildsTest extends BaseTest {

  def script

  // Helper
  private Integer extractBuild(Map args) {
    def selector = args.selector?.toString()
    return selector.replaceAll('\\D', '').toInteger()
  }

  @Before
  void setUp() throws Exception {
    super.setUp()

    script = loadScript('vars/infra.groovy')

    // Used by copyArtifacts
    helper.registerAllowedMethod('specific', [String.class], { it })
  }

  @Test
  void testFirstBuildReturnsZero() {
    env.BUILD_NUMBER = '1'

    def result = script.retrieveArtifactsFromPreviousBuilds([
      archiveName: 'file.zip',
      jobName: 'PR_123',
    ])

    assert result == 0
    assertMethodCallContainsPattern('echo', 'First build of PR_123, no file.zip available yet')
    assert !assertMethodCall('copyArtifacts')
    assert !assertMethodCallContainsPattern('echo', 'file.zip found in PR_123')
  }

  @Test
  void testArtifactFoundInPreviousBuild() {
    env.BUILD_NUMBER = '5'

    helper.registerAllowedMethod('copyArtifacts', [Map.class], { true })

    def result = script.retrieveArtifactsFromPreviousBuilds([
      archiveName: 'file.zip',
      jobName: 'PR_123',
    ])

    assert result == 4
    assertMethodCallOccurrences('copyArtifacts', 1)
    assertMethodCallContainsPattern('echo', 'Trying to retrieve file.zip from PR_123#5...')
    assertMethodCallContainsPattern('echo', 'file.zip found in PR_123#5')
  }

  @Test
  void testArtifactFoundAfterSeveralAttempts() {
    env.BUILD_NUMBER = '5'

    def calls = []

    helper.registerAllowedMethod('copyArtifacts', [Map.class], { args ->
      def build = extractBuild(args)
      calls << build

      if (build != 3) {
        throw new Exception('not found')
      }
      return true
    })

    def result = script.retrieveArtifactsFromPreviousBuilds([
      archiveName: 'file.zip',
      jobName: 'PR_123',
    ])

    assert result == 3
    assert calls == [4, 3]
    assertMethodCallContainsPattern('echo', 'file.zip found in PR_123#3')
  }

  @Test
  void testMasterBranchLimitedTo50Builds() {
    env.BUILD_NUMBER = '100'

    def checked = []

    helper.registerAllowedMethod('copyArtifacts', [Map.class], { args ->
      def build = extractBuild(args)
      checked << build
      throw new Exception('not found')
    })

    script.retrieveArtifactsFromPreviousBuilds([
      archiveName: 'file.zip',
      jobName: 'master',
    ])

    assert checked.min() >= 50
    assertMethodCallContainsPattern('echo', 'No file.zip found in any build of master')
  }

  @Test
  void testMainBranchLimitedTo50Builds() {
    env.BUILD_NUMBER = '100'

    def checked = []

    helper.registerAllowedMethod('copyArtifacts', [Map.class], { args ->
      def build = extractBuild(args)
      checked << build
      throw new Exception('not found')
    })

    script.retrieveArtifactsFromPreviousBuilds([
      archiveName: 'file.zip',
      jobName: 'my-folder/my-job/main',
      primaryBranchName: 'main'
    ])

    assert checked.min() >= 50
    assertMethodCallContainsPattern('echo', 'No file.zip found in any build of my-folder/my-job/main')
  }

  @Test
  void testStopsAfterArtifactFound() {
    env.BUILD_NUMBER = '6'

    def calls = []

    helper.registerAllowedMethod('copyArtifacts', [Map.class], { args ->
      def build = extractBuild(args)
      calls << build

      if (build != 3) {
        throw new Exception('not found')
      }
      return true
    })

    def result = script.retrieveArtifactsFromPreviousBuilds([
      archiveName: 'file.zip',
      jobName: 'PR_123',
    ])

    assert result == 3
    assert calls == [5, 4, 3]
    assertMethodCallContainsPattern('echo', 'file.zip found in PR_123#3')
  }

  @Test
  void testFailsWhenMissingArgs() {
    try {
      script.retrieveArtifactsFromPreviousBuilds([:])
      assert false
    } catch (Exception e) {
      assert e.message.contains('Missing args')
    }
    try {
      script.retrieveArtifactsFromPreviousBuilds([
        archiveName: 'file.zip'
      ])
      assert false
    } catch (Exception e) {
      assert e.message.contains('Missing args')
    }
    try {
      script.retrieveArtifactsFromPreviousBuilds([
        jobName: 'PR_123'
      ])
      assert false
    } catch (Exception e) {
      assert e.message.contains('Missing args')
    }
  }
}
