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
import java.text.SimpleDateFormat

class BuildDockerAndPublishImageStepTests extends BaseTest {
  static final String scriptName = 'vars/buildDockerAndPublishImage.groovy'
  static final String testImageName = 'bitcoinMinerImage'
  static final String defaultDockerRegistry = 'jenkinsciinfra'
  static final String fullTestImageName = defaultDockerRegistry + '/' + testImageName
  static final String defaultGitTag = '1.0.0'


  def infraConfigMock
  def dateMock
  def simpleDateMock

  def mockedTimestamp = '1431288000000'
  def mockedSimpleDate = '2022-02-02T20:20:20222'

  @Rule
  public ExpectedException thrown = ExpectedException.none()

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    // Mock Pipeline methods which are not already declared in the parent class
    helper.registerAllowedMethod('hadoLint', [Map.class], { m -> m })
    helper.registerAllowedMethod('fileExists', [String.class], { true })
    helper.addShMock('jx-release-version', defaultGitTag , 0)
    helper.addShMock('git remote -v | grep origin | grep push | sed \'s/^origin\\s//\' | sed \'s/\\s(push)//\'', 'https://github.com/org/repository.git', 0)
    helper.addShMock('gh api /repos/org/repository/releases | jq -e -r \'.[] | select(.draft == true and .name == "next") | .id\'', '12345', 0)
    addEnvVar('WORKSPACE', '/tmp')

    // Define mocks/stubs for the data objects
    infraConfigMock = new StubFor(InfraConfig.class)


    dateMock = new StubFor(Date.class)
    dateMock.demand.with {
      getTime{ mockedTimestamp }
    }

    simpleDateMock = new StubFor(SimpleDateFormat.class)
    simpleDateMock.demand.with {
      format{ mockedSimpleDate }
    }
  }

  void withMocks(Closure body) {
    infraConfigMock.use {
      dateMock.use {
        simpleDateMock.use {
          body()
        }
      }
    }
  }

  void verifyMocks() {
    infraConfigMock.expect.verify()
    dateMock.expect.verify()
    simpleDateMock.expect.verify()
  }

  void mockPrincipalBranch() {
    addEnvVar('BRANCH_IS_PRIMARY', 'true')

    infraConfigMock.demand.with {
      getDockerRegistry{ defaultDockerRegistry }
    }
  }

  void mockTag(String gitTag = defaultGitTag) {
    addEnvVar('TAG_NAME', gitTag)

    infraConfigMock.demand.with {
      getDockerRegistry{ defaultDockerRegistry }
    }
  }

  // Return if the set of methods expected for ALL pipeline run have been detected in the callstack
  Boolean assertBaseWorkflow() {
    return assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile') \
      && assertMethodCallContainsPattern('sh','make lint') \
      && assertMethodCallContainsPattern('sh','make build') \
      && assertMethodCallContainsPattern('sh','"${CONTAINER_BIN}" login') \
      && assertMethodCallContainsPattern('sh','"${CONTAINER_BIN}" logout') \
      && assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}")
  }

  // Return if the mocked pipeline ran in a container agent with the `img` containerless engine
  Boolean assertContainerAgent() {
    return assertMethodCallContainsPattern('containerTemplate', 'jenkinsciinfra/builder:') \
      && assertMethodCallContainsPattern('withEnv', 'CONTAINER_BIN=img') \
      && assertMethodCallContainsPattern('withEnv', 'CST_DRIVER=tar') \
      && !assertMethodCallContainsPattern('withEnv', 'HADOLINT_BIN=docker run --rm hadolint/hadolint:latest hadolint')
  }

  // Return if the mocked pipeline ran in a VM agent with the Docker Engine
  Boolean assertContainerVM(expectedNodeLabelPattern = 'docker') {
    return assertMethodCallContainsPattern('node', expectedNodeLabelPattern) \
      && assertMethodCallContainsPattern('withEnv', 'CONTAINER_BIN=docker') \
      && assertMethodCallContainsPattern('withEnv', 'CST_DRIVER=docker') \
      && assertMethodCallContainsPattern('withEnv', 'HADOLINT_BIN=docker run --rm hadolint/hadolint:latest hadolint')
  }

  // Return if the usual static checks had been recorded with the usual pattern
  Boolean assertRecordIssues(String imageName = testImageName) {
    return assertMethodCallContainsPattern('recordIssues', "{enabledForFailure=true, aggregatingResults=false, tool={id=${imageName}-hadolint-${mockedTimestamp}, pattern=${imageName}-hadolint-${mockedTimestamp}.json}}")
  }

  // return if the "make deploy" was detected with the provided argument as image name
  Boolean assertMakeDeploy(String expectedImageName = fullTestImageName) {
    return assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=' + expectedImageName + ' make deploy')
  }

  Boolean assertTagPushed(String newVersion) {
    return assertMethodCallContainsPattern('echo','Configuring credential.helper') \
      && assertMethodCallContainsPattern('echo',"Tagging New Version: ${newVersion}") \
      && assertMethodCallContainsPattern('sh',"git tag ${newVersion}") \
      && assertMethodCallContainsPattern('echo','Pushing Tag') \
      && assertMethodCallContainsPattern('sh','git push origin --tags')
  }

  Boolean assertReleaseCreated() {
    return assertMethodCallContainsPattern('stage','GitHub Release') \
      && assertMethodCallContainsPattern('withCredentials', 'GITHUB_TOKEN') \
      && assertMethodCallContainsPattern('withCredentials', 'GITHUB_USERNAME') \
      && assertMethodCallContainsPattern('sh', 'gh api /repos/')
  }

  @Test
  void itBuildsAndDeploysWithDefaultConfigOnPrincipalBranch() throws Exception {
    def script = loadScript(scriptName)

    mockPrincipalBranch()

    withMocks {
      script.call(testImageName)
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the common workflow run as expected
    assertTrue(assertBaseWorkflow())
    assertTrue(assertContainerAgent())

    // And the expected environment variable defined to their defaults
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DIR=.'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DOCKERFILE=Dockerfile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_PLATFORM=linux/amd64'))

    // And generated reports are recorded
    assertTrue(assertRecordIssues())

    // And the deploy step called
    assertTrue(assertMakeDeploy())

    // But no release created automatically
    assertFalse(assertTagPushed(defaultGitTag))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysWithDefaultConfigAndTagInImageNameOnPrincipalBranch() throws Exception {
    def script = loadScript(scriptName)
    def customImageNameWithTag = testImageName + ':3.141'
    def fullCustomImageName = 'jenkinsciinfra/' + customImageNameWithTag

    mockPrincipalBranch()

    withMocks{
      script.call(customImageNameWithTag)
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the common workflow run as expected
    assertTrue(assertBaseWorkflow())
    assertTrue(assertContainerAgent())

    // And generated reports are recorded with named without ':' but '-' instead
    assertTrue(assertRecordIssues(customImageNameWithTag.replaceAll(':','-')))

    // With the deploy step called with the correct image name
    assertTrue(assertMakeDeploy(fullCustomImageName))

    // But no tag pushed
    assertFalse(assertTagPushed(defaultGitTag))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysWithAutomaticSemanticTagAndReleaseOnPrincipalBranch() throws Exception {
    def script = loadScript(scriptName)

    mockPrincipalBranch()

    withMocks{
      script.call(testImageName, [
        automaticSemanticVersioning: true,
        gitCredentials: 'git-itbuildsanddeployswithautomaticsemantictagandreleaseonprincipalbranch',
      ])
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the common workflow run as expected
    assertTrue(assertBaseWorkflow())
    assertTrue(assertContainerAgent())

    // And generated reports are recorded
    assertTrue(assertRecordIssues())

    // And the deploy step called
    assertTrue(assertMakeDeploy())

    // And the tag pushed
    assertTrue(assertTagPushed(defaultGitTag))

    // But no release created (no tag triggering the build)
    assertFalse(assertReleaseCreated())

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysImageWithCustomConfigOnPrincipalBranch() throws Exception {
    def script = loadScript(scriptName)

    mockPrincipalBranch()

    withMocks{
      script.call(testImageName, [
        dockerfile: 'build.Dockerfile',
        imageDir: 'docker/',
        platform: 'linux/s390x',
        automaticSemanticVersioning: true,
        gitCredentials: 'git-creds',
      ])
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the common workflow run as expected
    assertTrue(assertBaseWorkflow())
    assertTrue(assertContainerAgent())

    // And the environement variables set with the custom configuration values
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DIR=docker/'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DOCKERFILE=build.Dockerfile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_PLATFORM=linux/s390x'))

    // But no tag and no deploy called (branch or PR)
    assertTrue(assertMakeDeploy())
    assertTrue(assertTagPushed(defaultGitTag))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itDoesNotDeployNorReleaseWhenNotOnPrincipalBranch() throws Exception {
    def script = loadScript(scriptName)

    withMocks{
      script.call(testImageName, [
        automaticSemanticVersioning: true,
        gitCredentials: 'git-credentials',
      ])
    }
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With the common workflow run as expected
    assertTrue(assertBaseWorkflow())
    assertTrue(assertContainerAgent())

    // But no deploy step called for latest
    assertFalse(assertMakeDeploy())

    // And no release (no tag)
    assertFalse(assertTagPushed(defaultGitTag))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysAndReleasesWhenTriggeredByTagAndSemVerEnabled() throws Exception {
    def script = loadScript(scriptName)

    mockTag()

    withMocks{
      script.call(testImageName, [
        automaticSemanticVersioning: true,
        gitCredentials: 'git-itbuildsanddeploysandreleaseswhentriggeredbytagandsemverenabled',
      ])
    }
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With the common workflow run as expected
    assertTrue(assertBaseWorkflow())
    assertTrue(assertContainerAgent())

    // And the deploy step called for latest
    assertTrue(assertMakeDeploy("${fullTestImageName}:${defaultGitTag}"))

    // And the release is created (tag triggering the build)
    assertTrue(assertReleaseCreated())

    // But no tag  pushed
    assertFalse(assertTagPushed(defaultGitTag))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itDeploysWithCorrectNameWhenTriggeredByTagAndImagenameHasTag() throws Exception {
    def script = loadScript(scriptName)
    def customImageNameWithTag = testImageName + ':3.141'
    def fullCustomImageName = 'jenkinsciinfra/' + customImageNameWithTag
    def customGitTag = 'rc1-1.0.0'

    mockTag(customGitTag)

    withMocks{
      script.call(customImageNameWithTag)
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the deploy step called with the correct image name
    assertTrue(assertMakeDeploy("${fullCustomImageName}-${customGitTag}"))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itSkipTestStageIfNoSpecificCSTFile() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with a default configuration and no cst.yml file found
    helper.registerAllowedMethod('fileExists', [String.class], { s -> return !s.contains('/cst.yml') })

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
  void itFailFastButRecordReportWhenLintFails() throws Exception {
    def script = loadScript(scriptName)

    helper.addShMock('make lint', '', 1)

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
    assertTrue(assertMethodCallContainsPattern('recordIssues', '{enabledForFailure=true, aggregatingResults=false, tool={id=hadolint-bitcoinMinerImage, pattern=/tmp/bitcoinMinerImage-hadolint.json}}'))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysWithDockerEngineOnPrincipalBranch() throws Exception {
    def script = loadScript(scriptName)

    mockPrincipalBranch()

    withMocks {
      script.call(testImageName, [
        useContainer: false,
      ])
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the common workflow run as expected
    assertTrue(assertBaseWorkflow())
    assertTrue(assertContainerVM())

    // And the expected environment variables set to their default values
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DIR=.'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DOCKERFILE=Dockerfile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_PLATFORM=linux/amd64'))

    // And generated reports recorded
    assertTrue(assertRecordIssues())

    // And the deploy step called
    assertTrue(assertMakeDeploy())

    // But no release created automatically
    assertFalse(assertTagPushed(defaultGitTag))

    // And all mocked/stubbed methods been called
    verifyMocks()
  }

  @Test
  void itBuildsOnlyOnChangeRequestWithWindowsContainers() throws Exception {
    def script = loadScript(scriptName)

    withMocks {
      script.call(testImageName, [
        useContainer: false,
        agentLabels: 'docker-windows',
      ])
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the common workflow run as expected
    assertTrue(assertBaseWorkflow())
    assertTrue(assertContainerVM('docker-windows'))

    // And the expected environment variables set to their default values
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DIR=.'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DOCKERFILE=Dockerfile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_PLATFORM=linux/amd64'))

    // And generated reports recorded
    assertTrue(assertRecordIssues())

    // But no deploy step called (not on principal branch)
    assertFalse(assertMakeDeploy())

    // But no release created automatically
    assertFalse(assertTagPushed(defaultGitTag))

    // And all mocked/stubbed methods been called
    verifyMocks()
  }
}
