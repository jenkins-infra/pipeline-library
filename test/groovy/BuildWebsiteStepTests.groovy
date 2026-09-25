import mock.Infra
import org.junit.Before
import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

class BuildWebsiteStepTests extends BaseTest {
  static final String scriptName = 'vars/buildWebsite.groovy'
  static final String defaultDeployFolder = 'public'

  Infra infraMock

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    infraMock = new Infra()
    binding.setProperty('infra', infraMock)

    // Default: npm project (no yarn.lock)
    helper.registerAllowedMethod('fileExists', [String.class], { s -> false })
    helper.registerAllowedMethod('styleLint', [Map.class], { 'styleLint' })
    helper.registerAllowedMethod('publishBuildStatusReport', [], { true })
    helper.registerAllowedMethod('checkout', [Object.class], { true })
    helper.registerAllowedMethod('string', [Map.class], { m -> m })
  }

  void mockPrincipalBranch() {
    addEnvVar('BRANCH_IS_PRIMARY', 'true')
  }

  void mockPullRequest() {
    addEnvVar('CHANGE_ID', '1234')
  }

  @Test
  void it_configures_short_lived_builds_by_default() throws Exception {
    def script = loadScript(scriptName)

    script.call([deployFolder: defaultDeployFolder])
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('disableConcurrentBuilds', 'abortPrevious=true'))
    assertTrue(assertMethodCallContainsPattern('logRotator', 'numToKeepStr=5'))
    // Not on the primary branch: no cron trigger
    assertFalse(assertMethodCallContainsPattern('cron', '@daily'))
  }

  @Test
  void it_keeps_more_builds_and_does_not_abort_previous_ones_on_primary_branch() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()

    script.call([deployFolder: defaultDeployFolder])
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('disableConcurrentBuilds', 'abortPrevious=false'))
    assertTrue(assertMethodCallContainsPattern('logRotator', 'numToKeepStr=20'))
    assertTrue(assertMethodCallContainsPattern('cron', '@daily'))
  }

  @Test
  void it_keeps_more_builds_and_does_not_abort_previous_ones_on_npm_release_branches() throws Exception {
    def script = loadScript(scriptName)
    addEnvVar('BRANCH_NAME', 'release-branch')

    script.call([deployFolder: defaultDeployFolder, releaseToNpmFromBranches: ['release-branch']])
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('disableConcurrentBuilds', 'abortPrevious=false'))
    assertTrue(assertMethodCallContainsPattern('logRotator', 'numToKeepStr=20'))
    // Cron scheduling stays primary-branch only, even though this branch releases to NPM
    assertFalse(assertMethodCallContainsPattern('cron', '@daily'))
  }

  @Test
  void it_warns_when_deployFolder_is_missing() throws Exception {
    def script = loadScript(scriptName)
    script.call([:])
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('echo', 'WARNING: buildWebsite requires a "deployFolder" parameter'))
  }

  @Test
  void it_builds_successfully_with_defaults_on_principal_branch() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()

    script.call([deployFolder: defaultDeployFolder])
    printCallStack()

    assertJobStatusSuccess()

    // No warning about the missing deployFolder
    assertFalse(assertMethodCallContainsPattern('echo', 'WARNING: buildWebsite requires a "deployFolder" parameter'))

    // Default env vars
    assertTrue(assertMethodCallContainsPattern('withEnv', '[TZ=UTC, NODE_ENV=production]'))

    // npm is used by default (no yarn.lock)
    assertTrue(assertMethodCallContainsPattern('sh', 'npm ci --include=dev'))
    assertTrue(assertMethodCallContainsPattern('sh', 'npm run build'))
    assertTrue(assertMethodCallContainsPattern('sh', 'npm run test --if-present'))
    assertTrue(assertMethodCallContainsPattern('sh', 'npm run lint --if-present'))

    // All the stages run in order
    assertTrue(assertMethodCallContainsPattern('stage', 'Checkout'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Typos check'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Dependencies install'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Lint'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Build'))
    assertTrue(assertMethodCallContainsPattern('stage', 'Test'))

    // Pre-build command dispatch happens as part of the Build stage
    assertTrue(infraMock.maybeWebsitePreBuildCommandCalled)

    // Deployed in production, on the primary branch, outside of any PR
    assertTrue(assertMethodCallContainsPattern('stage', 'Deploy production'))
    assertEquals(defaultDeployFolder, infraMock.deployedFolder)

    // No coverage stage outside ci.jenkins.io
    assertFalse(assertMethodCallContainsPattern('stage', 'Coverage'))

    // No release, releaseToNpmFromBranches is empty by default
    assertFalse(assertMethodCallContainsPattern('stage', 'Release'))
    assertFalse(infraMock.releaseToNpmCalled)

    // Publish build status report on the primary branch
    assertTrue(assertMethodCall('publishBuildStatusReport'))
  }

  @Test
  void it_allows_to_override_default_env_vars() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()

    script.call([deployFolder: defaultDeployFolder, customEnvsProduction: ['TZ=CEST', 'NODE_ENV=other']])
    printCallStack()

    assertJobStatusSuccess()

    // Default env vars are overriden (last declaration wins)
    assertTrue(assertMethodCallContainsPattern('withEnv', '[TZ=UTC, NODE_ENV=production, TZ=CEST, NODE_ENV=other]'))
  }

  @Test
  void it_deploys_preview_on_pull_requests_and_skips_build_status_report() throws Exception {
    def script = loadScript(scriptName)
    mockPullRequest()

    script.call([deployFolder: defaultDeployFolder])
    printCallStack()

    assertJobStatusSuccess()

    assertTrue(assertMethodCallContainsPattern('withEnv', '[TZ=UTC, NODE_ENV=development]'))

    assertTrue(assertMethodCallContainsPattern('stage', 'Deploy preview'))
    assertFalse(assertMethodCallContainsPattern('stage', 'Deploy production'))

    // Not on the primary branch: no build status report
    assertFalse(assertMethodCall('publishBuildStatusReport'))
  }

  @Test
  void it_uses_yarn_when_a_yarn_lock_file_is_present() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    helper.registerAllowedMethod('fileExists', [String.class], { s ->
      s == 'yarn.lock'
    })
    helper.registerAllowedMethod('sh', [Map.class], { m -> 0 })

    script.call([deployFolder: defaultDeployFolder])
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('echo', 'Package manager determined by checking if yarn.lock exists or not: yarn'))
    assertTrue(assertMethodCallContainsPattern('sh', 'yarn install --immutable'))
    assertTrue(assertMethodCallContainsPattern('sh', 'yarn run build'))
  }

  @Test
  void it_skips_typos_check_when_disabled() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()

    script.call([deployFolder: defaultDeployFolder, typosCheck: false])
    printCallStack()

    assertJobStatusSuccess()
    assertFalse(assertMethodCallContainsPattern('stage', 'Typos check'))
  }

  @Test
  void it_skips_lint_when_disabled() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()

    script.call([deployFolder: defaultDeployFolder, lint: false])
    printCallStack()

    assertJobStatusSuccess()
    assertFalse(assertMethodCallContainsPattern('stage', 'Lint'))
  }

  @Test
  void it_records_issues_and_stops_the_build_when_lint_fails() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    helper.registerAllowedMethod('sh', [String.class], { s ->
      if (s == 'npm run lint --if-present') {
        throw new Exception('lint failed')
      }
      return s
    })

    try {
      script.call([deployFolder: defaultDeployFolder])
    } catch (e) {
      // NOOP: recordIssues(stopBuild: true) is expected to abort the build
    }
    printCallStack()

    assertTrue(assertMethodCallContainsPattern('recordIssues', 'stopBuild=true'))
  }

  @Test
  void it_save_junit_when_junitResultsPattern_is_set() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()

    script.call([deployFolder: defaultDeployFolder, junitResultsPattern: 'test-results/**/*.xml'])
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('junit', 'test-results/**/*.xml'))
  }

  @Test
  void it_skips_junit_when_junitResultsPattern_is_empty() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()

    script.call([deployFolder: defaultDeployFolder, junitResultsPattern: ''])
    printCallStack()

    assertJobStatusSuccess()
    assertFalse(assertMethodCall('junit'))
  }

  @Test
  void it_records_coverage_when_a_coveragePath_is_provided_on_ci_controller() throws Exception {
    infraMock = new Infra(ci: true)
    binding.setProperty('infra', infraMock)
    def script = loadScript(scriptName)
    mockPrincipalBranch()

    script.call([deployFolder: defaultDeployFolder, coveragePath: 'coverage/cobertura.xml'])
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('stage', 'Coverage'))
    assertTrue(assertMethodCallContainsPattern('sh', 'npm run coverage --if-present'))
    assertTrue(assertMethodCallContainsPattern('recordCoverage', 'coverage/cobertura.xml'))
  }

  @Test
  void it_skips_coverage_off_ci_controller_even_with_a_coveragePath() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()

    script.call([deployFolder: defaultDeployFolder, coveragePath: 'coverage/cobertura.xml'])
    printCallStack()

    assertJobStatusSuccess()
    assertFalse(assertMethodCallContainsPattern('stage', 'Coverage'))
  }

  @Test
  void it_releases_to_npm_on_a_configured_branch() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    addEnvVar('BRANCH_NAME', 'main')

    script.call([deployFolder: defaultDeployFolder, releaseToNpmFromBranches: ['main']])
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('stage', 'Release'))
    assertTrue(infraMock.releaseToNpmCalled)
  }

  @Test
  void it_does_not_release_to_npm_when_branch_does_not_match() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    addEnvVar('BRANCH_NAME', 'main')

    script.call([deployFolder: defaultDeployFolder, releaseToNpmFromBranches: ['other-branch']])
    printCallStack()

    assertJobStatusSuccess()
    assertFalse(assertMethodCallContainsPattern('stage', 'Release'))
    assertFalse(infraMock.releaseToNpmCalled)
  }

  @Test
  void it_uses_the_agent_label_provided_by_infra() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()

    script.call([deployFolder: defaultDeployFolder])
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('node', 'website-agent-0'))
  }

  @Test
  void it_does_not_bind_additional_production_credentials_on_pull_requests() throws Exception {
    def script = loadScript(scriptName)
    mockPullRequest()

    script.call([
      deployFolder: defaultDeployFolder,
      additionalCredentialsIdsAndVars: ['algolia-write-key': 'GATSBY_ALGOLIA_WRITE_KEY'],
    ])
    printCallStack()

    assertJobStatusSuccess()
    assertFalse(assertMethodCallContainsPattern('withCredentials', 'algolia-write-key'))
  }

  @Test
  void it_does_not_bind_additional_production_credentials_on_ci_controller() throws Exception {
    infraMock = new Infra(ci: true)
    binding.setProperty('infra', infraMock)
    def script = loadScript(scriptName)
    mockPrincipalBranch()

    script.call([
      deployFolder: defaultDeployFolder,
      additionalCredentialsIdsAndVars: ['algolia-write-key': 'GATSBY_ALGOLIA_WRITE_KEY'],
    ])
    printCallStack()

    assertJobStatusSuccess()
    assertFalse(assertMethodCallContainsPattern('withCredentials', 'algolia-write-key'))
  }

  @Test
  void it_binds_additional_production_credentials_when_configured() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()

    script.call([
      deployFolder: defaultDeployFolder,
      additionalCredentialsIdsAndVars: ['algolia-write-key': 'GATSBY_ALGOLIA_WRITE_KEY'],
    ])
    printCallStack()

    assertJobStatusSuccess()
    assertTrue(assertMethodCallContainsPattern('withCredentials', 'credentialsId=algolia-write-key, variable=GATSBY_ALGOLIA_WRITE_KEY'))
  }
}
