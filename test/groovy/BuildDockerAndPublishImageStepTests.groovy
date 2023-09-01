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
  static final String defaultDockerRegistryNamespace = 'jenkinsciinfra'
  static final String fullTestImageName = defaultDockerRegistryNamespace + '/' + testImageName
  static final String defaultGitTag = '1.0.0'
  static final String defaultGitTagIncludingImageName = '1.0.0-bitcoinminerimage'
  static final String defaultNextVersionCommand = 'jx-release-version'
  static final String defaultOrigin = 'https://github.com/org/repository.git'
  static final String defaultReleaseId = '12345'

  def infraConfigMock
  def dateMock
  def simpleDateMock

  def mockedTimestamp = '1431288000000'
  def mockedSimpleDate = '2022-02-02T20:20:20222'

  @Rule
  public ExpectedException thrown = ExpectedException.none()

  String shellMock(String command, Boolean noReleaseDraft = false) {
    switch (command) {
      case {command.contains('git tag --list')}:
        return defaultGitTag
        break
      case {command.contains('gh api -X PATCH')}:
        return (noReleaseDraft ? '' : defaultReleaseId)
        break
      case {command.contains(defaultNextVersionCommand + ' -debug --previous-version')}:
        return defaultGitTagIncludingImageName
        break
      case defaultNextVersionCommand:
        return defaultGitTag
        break
      default:
        return command
    }
  }

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    // Mock Pipeline methods which are not already declared in the parent class
    helper.registerAllowedMethod('hadoLint', [Map.class], { m -> m })
    helper.registerAllowedMethod('fileExists', [String.class], { true })
    binding.setVariable('infra', ['withDockerPullCredentials': {body -> body()}, 'withDockerPushCredentials': {body ->body()}])
    helper.registerAllowedMethod('sh', [Map.class], { m ->
      return shellMock(m.script)
    })
    helper.registerAllowedMethod('powershell', [Map.class], { m ->
      return shellMock(m.script)
    })

    addEnvVar('WORKSPACE', '/tmp')

    // Define mocks/stubs for the data objects
    infraConfigMock = new StubFor(InfraConfig.class)
    infraConfigMock.demand.with {
      getDockerRegistryNamespace{ defaultDockerRegistryNamespace }
    }

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
  }

  void mockTag(String gitTag = defaultGitTag) {
    addEnvVar('TAG_NAME', gitTag)
  }

  // Return if the usual static checks had been recorded with the usual pattern
  Boolean assertRecordIssues(String imageName = fullTestImageName) {
    final String reportId = "${imageName}-hadolint-${mockedTimestamp}".replaceAll('/','-').replaceAll(':', '-')
    return assertMethodCallContainsPattern(
        'recordIssues',
        "{enabledForFailure=true, aggregatingResults=false, tool={id=${reportId}, pattern=${reportId}.json}}",
        )
  }

  Boolean assertTagPushed(String newVersion) {
    return assertMethodCallContainsPattern('echo','Configuring credential.helper') \
      && assertMethodCallContainsPattern('echo',"Tagging and pushing the new version: ${newVersion}") \
      && (assertMethodCallContainsPattern('sh','git config user.name "${GIT_USERNAME}"') || assertMethodCallContainsPattern('powershell','git config user.name "$env:GIT_USERNAME"')) \
      && (assertMethodCallContainsPattern('sh','git config user.email "jenkins-infra@googlegroups.com"') || assertMethodCallContainsPattern('powershell','git config user.email "jenkins-infra@googlegroups.com"')) \
      && (assertMethodCallContainsPattern('sh','git tag -a "${NEXT_VERSION}" -m "${IMAGE_NAME}"') || assertMethodCallContainsPattern('powershell','git tag -a "$env:NEXT_VERSION" -m "$env:IMAGE_NAME"')) \
      && (assertMethodCallContainsPattern('sh','git push origin --tags') || assertMethodCallContainsPattern('powershell','git push origin --tags'))
  }

  Boolean assertReleaseCreated() {
    return assertMethodCallContainsPattern('stage','GitHub Release') \
      && assertMethodCallContainsPattern('withCredentials', 'GITHUB_TOKEN') \
      && assertMethodCallContainsPattern('withCredentials', 'GITHUB_USERNAME') \
      && !assertMethodCallContainsPattern('echo', 'No next release draft found.')
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
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make bake-build'))

    assertTrue(assertMethodCallContainsPattern('node', 'docker'))

    // And the expected environment variable defined to their defaults
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DIR=.'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DOCKERFILE=Dockerfile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'BAKE_TARGETPLATFORMS=linux/amd64'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DEPLOY_NAME=' + fullTestImageName))

    // And generated reports are recorded
    assertTrue(assertRecordIssues())

    // And the deploy step called
    assertTrue(assertMethodCallContainsPattern('sh','make bake-deploy'))

    // And `unstash` isn't called
    assertFalse(assertMethodCall('unstash'))

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
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make bake-build'))

    assertTrue(assertMethodCallContainsPattern('node', 'docker'))
    // And generated reports are recorded with named without ':' but '-' instead
    assertTrue(assertRecordIssues(fullCustomImageName.replaceAll(':','-')))
    // With the deploy step called with the correct image name
    assertMethodCallContainsPattern('sh','make bake-deploy')
    assertMethodCallContainsPattern('withEnv', "IMAGE_DEPLOY_NAME=${fullCustomImageName}")

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
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make bake-build'))
    assertTrue(assertMethodCallContainsPattern('node', 'docker'))
    // And generated reports are recorded
    assertTrue(assertRecordIssues())
    // And the deploy step called
    assertTrue(assertMethodCallContainsPattern('sh','make bake-deploy'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DEPLOY_NAME=' +fullTestImageName))

    // And the tag pushed
    assertTrue(assertTagPushed(defaultGitTag))
    // But no release created (no tag triggering the build)
    assertFalse(assertReleaseCreated())
    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysWithAutomaticSemanticTagAndincludeImageNameInTagAndReleaseOnPrincipalBranch() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    withMocks{
      script.call(testImageName, [
        automaticSemanticVersioning: true,
        includeImageNameInTag: true,
        gitCredentials: 'git-itbuildsanddeployswithautomaticsemantictagandreleaseonprincipalbranch',
      ])
    }
    printCallStack()
    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()
    // With the common workflow run as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make bake-build'))

    assertTrue(assertMethodCallContainsPattern('node', 'docker'))
    // And generated reports are recorded
    assertTrue(assertRecordIssues())
    // And the deploy step called
    assertTrue(assertMethodCallContainsPattern('sh','make bake-deploy'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DEPLOY_NAME=' + fullTestImageName))

    // And the tag pushed
    assertTrue(assertTagPushed(defaultGitTagIncludingImageName))
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
        registryNamespace: 'jenkins',
      ])
    }
    final String expectedImageName = 'jenkins/' + testImageName
    printCallStack()
    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()
    // With the common workflow run as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make bake-build'))

    assertTrue(assertMethodCallContainsPattern('node', 'docker'))
    // And the environement variables set with the custom configuration values
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DIR=docker/'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DOCKERFILE=build.Dockerfile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'BAKE_TARGETPLATFORMS=linux/s390x'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_NAME=' + expectedImageName))
    // But no tag and no deploy called (branch or PR)
    assertTrue(assertMethodCallContainsPattern('sh','make bake-deploy'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DEPLOY_NAME=' + expectedImageName))

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
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make bake-build'))

    assertTrue(assertMethodCallContainsPattern('node', 'docker'))
    // But no deploy step called for latest
    assertFalse(assertMethodCallContainsPattern('sh','make bake-deploy'))
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
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make bake-build'))

    assertTrue(assertMethodCallContainsPattern('node', 'docker'))
    // And the deploy step called for latest
    assertTrue(assertMethodCallContainsPattern('sh','make bake-deploy'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DEPLOY_NAME=' + fullTestImageName))

    // And the release is created (tag triggering the build)
    assertTrue(assertReleaseCreated())
    // But no tag pushed
    assertFalse(assertTagPushed(defaultGitTag))
    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysButNoReleaseWhenTriggeredByTagAndSemVerEnabledButNoReleaseDrafter() throws Exception {
    helper.registerAllowedMethod('sh', [Map.class], { m ->
      return shellMock(m.script, true)
    })
    helper.registerAllowedMethod('powershell', [Map.class], { m ->
      return shellMock(m.script, true)
    })

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
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make bake-build'))

    assertTrue(assertMethodCallContainsPattern('node', 'docker'))
    // And the deploy step called for latest
    assertTrue(assertMethodCallContainsPattern('sh','make bake-deploy'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DEPLOY_NAME=' + fullTestImageName))

    // And the release is not created as no next release draft exists
    assertFalse(assertReleaseCreated())
    // But no tag pushed
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
    assertTrue(assertMethodCallContainsPattern('sh','make bake-deploy'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "IMAGE_DEPLOY_NAME=${fullCustomImageName}"))

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
  void itBuildsOnlyOnChangeRequestWithWindowsContainers() throws Exception {
    helper.registerAllowedMethod('isUnix', [], { false })
    def script = loadScript(scriptName)
    withMocks {
      script.call(testImageName, [
        agentLabels: 'docker-windows',
      ])
    }
    printCallStack()
    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()
    // With the common workflow run as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('powershell','make lint'))
    assertTrue(assertMethodCallContainsPattern('powershell','make build'))


    assertTrue(assertMethodCallContainsPattern('node', 'docker-windows'))
    // And the expected environment variables set to their default values
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DIR=.'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DOCKERFILE=Dockerfile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'BAKE_TARGETPLATFORMS=linux/amd64'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_NAME=' + fullTestImageName))
    // And generated reports recorded
    assertTrue(assertRecordIssues())
    // But no deploy step called (not on principal branch)
    assertFalse(assertMethodCallContainsPattern('sh','make deploy'))

    // But no release created automatically
    assertFalse(assertTagPushed(defaultGitTag))
    // And all mocked/stubbed methods been called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysWithUnstashOnPrincipalBranch() throws Exception {
    def script = loadScript(scriptName)
    helper.registerAllowedMethod('unstash', [String.class], { s -> s })

    mockPrincipalBranch()

    withMocks {
      script.call(testImageName, [
        unstash: 'stashName',
      ])
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the common workflow run as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make bake-build'))

    assertTrue(assertMethodCallContainsPattern('node', 'docker'))

    // And the expected environment variable defined to their defaults
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DIR=.'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DOCKERFILE=Dockerfile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'BAKE_TARGETPLATFORMS=linux/amd64'))

    // And generated reports are recorded
    assertTrue(assertRecordIssues())

    // And the deploy step called
    assertTrue(assertMethodCallContainsPattern('sh','make bake-deploy'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DEPLOY_NAME=' + fullTestImageName))

    // And `unstash` is called
    assertTrue(assertMethodCallContainsPattern('unstash', 'stashName'))

    // But no release created automatically
    assertFalse(assertTagPushed(defaultGitTag))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysImageWithCustomPlatformOnPrincipalBranch() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    withMocks{
      script.call(testImageName, [
        platform: 'linux/amd64,linux/arm64,linux/s390x',
        automaticSemanticVersioning: true,
      ])
    }
    printCallStack()
    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()
    // With the common workflow run as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make bake-build'))

    assertTrue(assertMethodCallContainsPattern('node', 'docker'))
    // And the environement variables set with the custom configuration values
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DIR=.'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DOCKERFILE=Dockerfile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'PLATFORMS=linux/amd64,linux/arm64,linux/s390x'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_NAME=' + fullTestImageName))
    // But no tag and no deploy called (branch or PR)
    assertTrue(assertMethodCallContainsPattern('sh','make bake-deploy'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DEPLOY_NAME=' + fullTestImageName))

    assertTrue(assertTagPushed(defaultGitTag))
    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysImageWithSpecifiedBakeFileOnPrincipalBranch() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    withMocks{
      script.call(testImageName, [
        dockerBakeFile: 'bake.yml',
      ])
    }

    //final String expectedImageName = 'jenkins/' + testImageName
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // // With the common workflow run as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make bake-build'))

    assertTrue(assertMethodCallContainsPattern('sh', 'make bake-build'))
    assertFalse(assertMethodCallContainsPattern('sh', 'make build'))
    assertTrue(assertMethodCallContainsPattern('sh', 'make bake-deploy'))
    assertFalse(assertMethodCallContainsPattern('sh', 'make deploy'))
    // // And the environement variables set with the custom configuration values
    assertTrue(assertMethodCallContainsPattern('withEnv', 'BAKE_TARGETPLATFORMS=linux/amd64'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'IMAGE_DOCKERFILE=Dockerfile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', 'DOCKER_BAKE_FILE=bake.yml'))
    // // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildWithWarningWithPlatform() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    withMocks{
      script.call(testImageName, [
        platform: 'linux/amd64',
      ])
    }

    printCallStack()

    // Then we expect a failing build
    assertJobStatusSuccess()

    // And the error message is shown
    assertTrue(assertMethodCallContainsPattern('echo', 'WARNING: `platform` is deprecated, use `targetplatforms` instead.'))
  }

  @Test
  void itFailWithBothPlatformAndTargetplatforms() throws Exception {
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    withMocks{
      script.call(testImageName, [
        platform: 'linux/amd64',
        targetplatforms: 'linux/arm64',
      ])
    }

    printCallStack()

    // Then we expect a failing build
    assertJobStatusFailure()

    // And the error message is shown
    assertTrue(assertMethodCallContainsPattern('echo', 'ERROR: Only one platform parameter is supported for now either platform or targetplatforms, prefer `targetplatforms`.'))
  }

  @Test
  void itFailWithWindowsAndMoreThanOnePlatform() throws Exception {
    helper.registerAllowedMethod('isUnix', [], { false })
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    withMocks{
      script.call(testImageName, [
        agentLabels: 'docker-windows',
        targetplatforms: 'linux/arm64,linux/amd64',
      ])
    }

    printCallStack()

    // Then we expect a failing build
    assertJobStatusFailure()

    // And the error message is shown
    assertTrue(assertMethodCallContainsPattern('echo', 'ERROR: with windows, only one platform can be specified within targetplatforms.'))
  }

  @Test
  void itFailsWithWindowsAndBakeOnPrincipalBranch() throws Exception {
    helper.registerAllowedMethod('isUnix', [], { false })
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    withMocks{
      script.call(testImageName, [
        dockerBakeFile: 'bake.yml',
        targetplatforms: 'windows/amd64',
        agentLabels: 'docker-windows',
      ])
    }

    printCallStack()

    // Then we expect a failing build
    assertJobStatusFailure()

    // And the error message is shown
    assertTrue(assertMethodCallContainsPattern('echo', 'ERROR: dockerBakeFile is not supported on windows.'))
  }

  @Test
  void itWarnIfWindowsAgentAndNotWindowsTarget() throws Exception {
    helper.registerAllowedMethod('isUnix', [], { false })
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    withMocks{
      script.call(testImageName, [
        targetplatforms: 'linux/amd64',
        agentLabels: 'docker-windows',
      ])
    }
    printCallStack()

    // Then we expect a failing build
    assertJobStatusSuccess()

    // And the error message is shown
    assertTrue(assertMethodCallContainsPattern('echo', 'WARNING: A \'windows\' agent is requested, but the \'platform(s)\' is set to'))
  }

  @Test
  void itWarnIfNotWindowsAgentButWindowsTarget() throws Exception {
    helper.registerAllowedMethod('isUnix', [], { false })
    def script = loadScript(scriptName)
    mockPrincipalBranch()
    withMocks{
      script.call(testImageName, [
        targetplatforms: 'windows/amd64',
      ])
    }
    printCallStack()

    // Then we expect a failing build
    assertJobStatusSuccess()

    // And the error message is shown
    assertTrue(assertMethodCallContainsPattern('echo', 'WARNING: The \'targetplatforms\' is set to \'windows/amd64\', but there isn\'t any \'windows\' agent requested.'))
  }

  @Test
  void itBuildsAndDeploysWithDefaultConfigAndTagInImageNameOnTagOnWindows() throws Exception {
    helper.registerAllowedMethod('isUnix', [], { false })
    def script = loadScript(scriptName)
    def customImageNameWithTag = testImageName + ':jdk8-nanoserver'
    def fullCustomImageName = 'jenkinsciinfra/' + customImageNameWithTag
    mockPrincipalBranch()
    mockTag()
    withMocks{
      script.call(customImageNameWithTag,[
        agentLabels: 'docker-windows',
        targetplatforms: 'windows/amd64',
      ])
    }
    printCallStack()
    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()
    // With the common workflow run as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('powershell','make lint'))
    assertTrue(assertMethodCallContainsPattern('powershell','make build'))

    assertTrue(assertMethodCallContainsPattern('node', 'docker'))
    // And generated reports are recorded with named without ':' but '-' instead
    assertTrue(assertRecordIssues(fullCustomImageName.replaceAll(':','-')))
    // With the deploy step called with the correct image name
    assertTrue(assertMethodCallContainsPattern('powershell','make deploy'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "IMAGE_DEPLOY_NAME=jenkinsciinfra/bitcoinMinerImage:jdk8-nanoserver-1.0.0"))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }

  @Test
  void itBuildsAndDeploysWithDefaultConfigOnTagOnWindows() throws Exception {
    helper.registerAllowedMethod('isUnix', [], { false })
    def script = loadScript(scriptName)
    def fullCustomImageName = 'jenkinsciinfra/' + testImageName
    mockPrincipalBranch()
    mockTag()
    withMocks{
      script.call(testImageName,[
        agentLabels: 'docker-windows',
        targetplatforms: 'windows/amd64',
      ])
    }
    printCallStack()
    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()
    // With the common workflow run as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "BUILD_DATE=${mockedSimpleDate}"))
    assertTrue(assertMethodCallContainsPattern('powershell','make lint'))
    assertTrue(assertMethodCallContainsPattern('powershell','make build'))

    assertTrue(assertMethodCallContainsPattern('node', 'docker'))
    // And generated reports are recorded with named without ':' but '-' instead
    assertTrue(assertRecordIssues(fullCustomImageName.replaceAll(':','-')))
    // With the deploy step called with the correct image name
    assertTrue(assertMethodCallContainsPattern('powershell','make deploy'))
    assertTrue(assertMethodCallContainsPattern('withEnv', "IMAGE_DEPLOY_NAME=jenkinsciinfra/bitcoinMinerImage:1.0.0"))

    // And all mocked/stubbed methods have to be called
    verifyMocks()
  }
}
