import org.junit.Before
import org.junit.Test
import groovy.mock.interceptor.StubFor

import io.jenkins.infra.*
import com.lesfurets.jenkins.unit.declarative.*

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.ExpectedException

import java.util.Date

class BuildDockerAndPublishImageStepTests extends BaseTest {
  static final String scriptName = "vars/buildDockerAndPublishImage.groovy"
  Map env = [:]
  static String testImageName = "bitcoinMinerImage"

  def infraConfig
  def dockerConfig
  def dateMock

  def mockedTimestamp = '1431288000000'

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    // Mock Pipeline method which are not already declared in the parent class
    helper.registerAllowedMethod('hadoLint', [Map.class], { m -> m })
    helper.registerAllowedMethod('libraryResource', [String.class], { s -> s == 'io/jenkins/infra/docker/jxNextVersionImage' ? 'jx-release-version:1.2.3' : '' })
    helper.registerAllowedMethod('fileExists', [String.class], { true })
    addEnvVar('WORKSPACE', '/tmp')

    // Define mocks/stubs for the data objects
    infraConfig = new StubFor(InfraConfig.class)

    dockerConfig = new StubFor(DockerConfig.class)
    dockerConfig.demand.with {
      getBuildDate{ '2021-01-04T15:10:55Z' }
      getDockerfile{ 'Dockerfile' }
      getCredentials{ '' }
      getImageName{ 'deathstar' }
      getPlatform{ 'linux/amd64' }
      getDockerImageDir{ '.' }
    }

    dateMock = new StubFor(Date.class)
    dateMock.demand.with {
      getTime{ mockedTimestamp }
    }
  }

  void withMocks(Closure body) {
    infraConfig.use {
      dockerConfig.use {
        dateMock.use {
          body()
        }
      }
    }
  }

  void verifyMocks() {
    infraConfig.expect.verify()
    dockerConfig.expect.verify()
    dateMock.expect.verify()
  }

  @Test
  void itBuildsAndDeploysWithDefaultConfig() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with the default Configuration
    addEnvVar('BRANCH_NAME', 'master')
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getFullImageName { 'jenkinsciinfra/deathstar' }
      getAutomaticSemanticVersioning{ false }
    }
    withMocks {
      script.call(testImageName)
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the static files read as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))

    // And the correct pod template defined
    assertTrue(assertMethodCallContainsPattern('containerTemplate', 'jenkinsciinfra/builder:latest'))
    assertTrue(assertMethodCallContainsPattern('containerTemplate', 'jx-release-version:1.2.3'))

    // And the make target called as shell steps
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make build'))
    assertTrue(assertMethodCallContainsPattern('sh','make test'))

    // With the deploy step called for latest
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=jenkinsciinfra/deathstar make deploy'))

    // And the img login methods
    assertTrue(assertMethodCallContainsPattern('sh','img login'))

    // And generated reports are recorded
    assertTrue(assertMethodCallContainsPattern('recordIssues', "{enabledForFailure=true, aggregatingResults=false, tool={id=deathstar-hadolint-${mockedTimestamp}, pattern=deathstar-hadolint-${mockedTimestamp}.json}}"))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itDeploysWithDefaultConfigAndTagInImageName() throws Exception {
    def script = loadScript(scriptName)
    def customImageNameWithTag = testImageName + ':3.141'
    def fullCustomImageName = 'jenkinsciinfra/' + customImageNameWithTag

    // when building a Docker Image with the default Configuration
    addEnvVar('BRANCH_NAME', 'master')
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getFullImageName { fullCustomImageName }
      getAutomaticSemanticVersioning{ false }
    }
    withMocks{
      script.call(customImageNameWithTag)
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the deploy step called with the correct image name
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=' + fullCustomImageName + ' make deploy'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysWithAutomaticSemanticRelease() throws Exception {
    def script = loadScript(scriptName)

    helper.addShMock("jx-release-version",'1.0.1', 0)

    // when building a Docker Image with the default Configuration
    addEnvVar('BRANCH_NAME', 'master')
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getMainBranch{ 'master' }
      getAutomaticSemanticVersioning{ true }
      getNextVersionCommand{ 'jx-release-version' }
      getMetadataFromSh{ '' }
      getGitCredentials{ 'git-credentials' }
      getFullImageName { 'jenkinsciinfra/deathstar' }
    }
    withMocks{
      script.call(testImageName)
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the static files read as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))

    // And the make target called as shell steps
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make build'))
    assertTrue(assertMethodCallContainsPattern('sh','make test'))

    // With the tag step called for latest
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=jenkinsciinfra/deathstar make deploy'))

    // Tag at the correct version
    assertTrue(assertMethodCallContainsPattern('echo','Configuring credential.helper'))
    assertTrue(assertMethodCallContainsPattern('echo','Tagging New Version: 1.0.1'))
    assertTrue(assertMethodCallContainsPattern('sh','git tag 1.0.1'))
    assertTrue(assertMethodCallContainsPattern('echo','Pushing Tag'))
    assertTrue(assertMethodCallContainsPattern('sh','git push origin --tags'))

    assertTrue(assertMethodCallContainsPattern('sh','img login'))

    // And generated reports are recorded
    assertTrue(assertMethodCallContainsPattern('recordIssues', "{enabledForFailure=true, aggregatingResults=false, tool={id=deathstar-hadolint-${mockedTimestamp}, pattern=deathstar-hadolint-${mockedTimestamp}.json}}"))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysWithAutomaticSemanticReleaseAndMetadataFromFile() throws Exception {
    def script = loadScript(scriptName)
    def metadataSh = 'cat Dockerfile | grep "FROM jenkins" | sed "s|FROM jenkins/jenkins:|+|" | sed "s|-jdk11||"'

    helper.addShMock("jx-release-version",'1.0.1', 0)
    helper.addShMock(metadataSh,'+2.280', 0)

    // when building a Docker Image with the default Configuration
    addEnvVar('BRANCH_NAME', 'master')
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getMainBranch{ 'master' }
      getAutomaticSemanticVersioning{ true }
      getMetadataFromSh{ metadataSh }
      getMetadataFromSh{ metadataSh }
      getGitCredentials{ 'git-credentials' }
      getFullImageName { 'jenkinsciinfra/deathstar' }
      getNextVersionCommand{ 'jx-release-version' }
    }
    withMocks{
      script.call(testImageName)
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the static files read as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))

    // And the make target called as shell steps
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make build'))
    assertTrue(assertMethodCallContainsPattern('sh','make test'))

    // With the tag step called for latest
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=jenkinsciinfra/deathstar make deploy'))

    // Tag with the correct version and metadata
    assertTrue(assertMethodCallContainsPattern('echo','Configuring credential.helper'))
    assertTrue(assertMethodCallContainsPattern('echo','Tagging New Version: 1.0.1+2.280'))
    assertTrue(assertMethodCallContainsPattern('sh','git tag 1.0.1+2.280'))
    assertTrue(assertMethodCallContainsPattern('echo','Pushing Tag'))
    assertTrue(assertMethodCallContainsPattern('sh','git push origin --tags'))

    // And the img login methods
    assertTrue(assertMethodCallContainsPattern('sh','img login'))

    // And generated reports are recorded
    assertTrue(assertMethodCallContainsPattern('recordIssues', "{enabledForFailure=true, aggregatingResults=false, tool={id=deathstar-hadolint-${mockedTimestamp}, pattern=deathstar-hadolint-${mockedTimestamp}.json}}"))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysImageWithCustomConfig() throws Exception {
    def script = loadScript(scriptName)

    addEnvVar('BRANCH_NAME', 'main')

    dockerConfig.demand.with {
      getMainBranch{ 'main' }
      getFullImageName { 'registry.company.com/deathstar' }
      getAutomaticSemanticVersioning{ false }
    }
    withMocks{
      script.call(testImageName, [
        registry: 'registry.company.com',
        dockerfile: 'build.Dockerfile',
        credentials: 'company-docker-registry-credz',
        mainBranch: 'main'
      ])
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the deploy step called for latest
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=registry.company.com/deathstar make deploy'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysImageWithCustomConfigAndTrailingSlashOnRegistry() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with a custom configuration but not on the principal branch
    addEnvVar('BRANCH_NAME', 'main')
    dockerConfig.demand.with {
      getMainBranch{ 'main' }
      getFullImageName { 'testregistry/deathstar' }
      getAutomaticSemanticVersioning{ false }
    }
    withMocks{
      script.call(testImageName, [
        registry: 'testregistry/',
        dockerfile: 'build.Dockerfile',
        credentials: 'company-docker-registry-credz',
        mainBranch: 'main'
      ])
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the deploy step called for latest
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=testregistry/deathstar make deploy'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itDoesNotDeployWhenNotOnPrincipalBranch() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with a default configuration but not on the principal branch
    addEnvVar('BRANCH_NAME', 'dev')
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getAutomaticSemanticVersioning{ false }
    }
    withMocks{
      script.call(testImageName)
    }
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With no deploy step called for latest
    assertFalse(assertMethodCallContainsPattern('sh','make deploy'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itDeployWithTagWhenTriggeredByTag() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with a default configuration, on the principal branch, triggered by a tag
    addEnvVar('BRANCH_NAME', 'master')
    addEnvVar('TAG_NAME', '1.0.0')
    dockerConfig.demand.with {
      getFullImageName { 'registry.company.com/deathstar' }
      getAutomaticSemanticVersioning{ false }
      getAutomaticSemanticVersioning{ false }
    }
    withMocks{
      script.call(testImageName)
    }
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With no deploy step called for latest
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=registry.company.com/deathstar:1.0.0 make deploy'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itDeployWithTagWhenTriggeredByTagAndSemanticRelease() throws Exception {
    def script = loadScript(scriptName)

    helper.addShMock('git remote -v | grep origin | grep push | sed \'s/^origin\\s//\' | sed \'s/\\s(push)//\'', 'https://github.com/org/repository.git', 0)
    helper.addShMock('gh api /repos/org/repository/releases | jq -e -r \'.[] | select(.draft == true and .name == "next") | .id\'', '12345', 0)

    // when building a Docker Image with a default configuration, on the principal branch, triggered by a tag
    addEnvVar('BRANCH_NAME', 'master')
    addEnvVar('TAG_NAME', '1.0.0')
    dockerConfig.demand.with {
      getFullImageName { 'registry.company.com/deathstar' }
      getAutomaticSemanticVersioning{ true }
      getAutomaticSemanticVersioning{ true }
      getMainBranch{ 'master' }
      getNextVersionCommand{ 'jx-release-version' }
      getMetadataFromSh{ '' }
      getGitCredentials{ 'git-creds' }
      getGitCredentials{ 'git-creds' }
    }
    withMocks{
      script.call(testImageName)
    }
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With no deploy step called for latest
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=registry.company.com/deathstar:1.0.0 make deploy'))
    assertTrue(assertMethodCallContainsPattern('sh','gh api /repos/org/repository/releases | jq -e -r \'.[] | select(.draft == true and .name == "next") | .id\''))
    assertTrue(assertMethodCallContainsPattern('sh','gh api -X PATCH -F draft=false -F name=1.0.0 -F tag_name=1.0.0 /repos/org/repository/releases/12345'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itDeployWithTagWhenTriggeredByTagAndSemanticReleaseWithoutReleaseDrafter() throws Exception {
    def script = loadScript(scriptName)

    helper.addShMock('git remote -v | grep origin | grep push | sed \'s/^origin\\s//\' | sed \'s/\\s(push)//\'', 'https://github.com/org/repository.git', 0)
    helper.addShMock('gh api /repos/org/repository/releases | jq -e -r \'.[] | select(.draft == true and .name == "next") | .id\'', '', 1)

    // when building a Docker Image with a default configuration, on the principal branch, triggered by a tag
    addEnvVar('BRANCH_NAME', 'master')
    addEnvVar('TAG_NAME', '1.0.0')
    dockerConfig.demand.with {
      getFullImageName { 'registry.company.com/deathstar' }
      getAutomaticSemanticVersioning{ true }
      getAutomaticSemanticVersioning{ true }
      getMainBranch{ 'master' }
      getNextVersionCommand{ 'jx-release-version' }
      getMetadataFromSh{ '' }
      getGitCredentials{ 'git-creds' }
      getGitCredentials{ 'git-creds' }
    }
    withMocks{
      script.call(testImageName)
    }
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With no deploy step called for latest
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=registry.company.com/deathstar:1.0.0 make deploy'))
    assertTrue(assertMethodCallContainsPattern('sh','gh api /repos/org/repository/releases | jq -e -r \'.[] | select(.draft == true and .name == "next") | .id\''))
    assertTrue(assertMethodCallContainsPattern('echo', 'Release named \'next\' does not exist'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itDeploysWithCorrectNameWhenTriggeredByTagAndImagenameHasTag() throws Exception {
    def script = loadScript(scriptName)
    def customImageNameWithTag = testImageName + ':3.141'
    def fullCustomImageName = 'jenkinsciinfra/' + customImageNameWithTag
    def gitTag = 'rc1-1.0.0'

    // when building a Docker Image with a default configuration, on the principal branch, triggered by a tag
    addEnvVar('BRANCH_NAME', 'master')
    addEnvVar('TAG_NAME', gitTag)
    dockerConfig.demand.with {
      getFullImageName { fullCustomImageName }
      getAutomaticSemanticVersioning{ false }
      getAutomaticSemanticVersioning{ false }
    }
    withMocks{
      script.call(customImageNameWithTag)
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the deploy step called with the correct image name
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=' + fullCustomImageName + '-' + gitTag + ' make deploy'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itSkipTestStageIfNoSpecificCSTFile() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with a default configuration and no cst.yml file found
    helper.registerAllowedMethod('fileExists', [String.class], { s -> return !s.contains('/cst.yml') })
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getAutomaticSemanticVersioning{ false }
    }
    withMocks{
      script.call(testImageName)
    }
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With only a common test stage
    assertFalse(assertMethodCallContainsPattern('withEnv','TEST_HARNESS=./cst.yml'))
    assertTrue(assertMethodCallContainsPattern('withEnv','TEST_HARNESS=/tmp/common-cst.yml'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itSkipTestStageIfNoCommonCSTFile() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with a default configuration and no cst.yml file found
    helper.registerAllowedMethod('fileExists', [String.class], { s -> return !s.contains('/common-cst.yml') })
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getAutomaticSemanticVersioning{ false }
    }
    withMocks{
      script.call(testImageName)
    }
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With only a specific tests stage
    assertTrue(assertMethodCallContainsPattern('withEnv','TEST_HARNESS=./cst.yml'))
    assertFalse(assertMethodCallContainsPattern('withEnv','TEST_HARNESS=/tmp/common-cst.yml'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itSkipAllTestStagesIfNoCSTFileAtAll() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with a default configuration and no cst.yml file found
    helper.registerAllowedMethod('fileExists', [String.class], { s -> return !(s.contains('/common-cst.yml') || s.contains('/cst.yml')) })
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getAutomaticSemanticVersioning{ false }
    }
    withMocks{
      script.call(testImageName)
    }
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With no test stage at all
    assertFalse(assertMethodCallContainsPattern('withEnv','TEST_HARNESS=./cst.yml'))
    assertFalse(assertMethodCallContainsPattern('withEnv','TEST_HARNESS=/tmp/common-cst.yml'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }


  @Test
  void itFailFastButRecordReportWhenLintFails() throws Exception {
    def script = loadScript(scriptName)

    helper.addShMock('make lint', '', 1)

    dockerConfig.demand.with {
      getMainBranch{ 'dev' }
      getAutomaticSemanticVersioning{ false }
    }

    // Job is expected to fail with an exception during the lint stage
    thrown.expect(Exception)
    thrown.expectMessage(containsString('Lint Failed'))

    withMocks{
      script.call(testImageName)
    }
    printCallStack()

    // Then we expect a failed build
    assertJobStatusFailure()

    // With a lint stage but no build stage
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertFalse(assertMethodCallContainsPattern('sh','make build'))

    // And a lint report recorded
    assertTrue(assertMethodCallContainsPattern('recordIssues', '{enabledForFailure=true, aggregatingResults=false, tool={id=hadolint-deathstar, pattern=/tmp/deathstar-hadolint.json}}'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itFailsFastWhenTestFails() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image which fails to pass the test stage on the master branch with default config
    addEnvVar('BRANCH_NAME', 'master')
    helper.addShMock('make test', '', 1)
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getAutomaticSemanticVersioning{ false }
      getNextVersionCommand{ 'jx-release-version' }
      getMetadataFromSh{ '' }
    }

    // Job is expected to fail with an exception during the lint stage
    thrown.expect(Exception)
    thrown.expectMessage(containsString('Test Failed'))

    withMocks{
      script.call(testImageName)
    }
    printCallStack()

    // Then we expect a failed build
    assertJobStatusFailure()

    // With a test stage
    assertTrue(assertMethodCallContainsPattern('sh','make test'))

    // And no deploy stage as we expect test failure to fail the pipeline
    assertFalse(assertMethodCallContainsPattern('sh','make deploy'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itUsesCustomImageFromCustomConfig() throws Exception {

    def script = loadScript(scriptName)

    addEnvVar('BRANCH_NAME', 'main')

    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getAutomaticSemanticVersioning{ false }
    }
    withMocks{
      script.call(testImageName, [
        builderImage: 'alpine:3.13',
        nextVersionImage: 'debian:slim',
      ])
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // And the correct pod template defined
    assertTrue(assertMethodCallContainsPattern('containerTemplate', 'alpine:3.13'))
    assertTrue(assertMethodCallContainsPattern('containerTemplate', 'debian:slim'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }
}
