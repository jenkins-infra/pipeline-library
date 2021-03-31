

import org.junit.Before
import org.junit.Test
import groovy.mock.interceptor.StubFor

import io.jenkins.infra.*
import com.lesfurets.jenkins.unit.declarative.*

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertNotNull
import static org.junit.Assert.assertTrue

class BuildDockerAndPublishImageStepTests extends BaseTest {
  static final String scriptName = "vars/buildDockerAndPublishImage.groovy"
  Map env = [:]
  static String testImageName = "bitcoinMinerImage"

  def infraConfig
  def dockerConfig

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    // Mock Pipeline method which are not already declared in the parent class
    helper.registerAllowedMethod('hadoLint', [Map.class], { m -> m.pattern })
    helper.registerAllowedMethod('libraryResource', [String.class], { '' })
    helper.registerAllowedMethod('fileExists', [String.class], { true })

    // Define mocks/stubs for the data objects
    infraConfig = new StubFor(InfraConfig.class)
    dockerConfig = new StubFor(DockerConfig.class)
    dockerConfig.demand.with {
      getBuildDate{ '2021-01-04T15:10:55Z' }
      getDockerfile{ 'Dockerfile' }
      getCredentials{ '' }
      getImageName{ 'deathstar' }
      getPlatform{ 'linux/amd64' }
    }
  }

  @Test
  void itBuildsAndDeploysWithDefaultConfig() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with the default Configuration
    addEnvVar('BRANCH_NAME', 'master')
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getMainBranch{ 'master' }
      getMainBranch{ 'master' }
      getMetadataFromSh{ '' }
      getFullImageName { 'jenkinsciinfra/deathstar' }
      getAutomaticSemanticVersioning{ false }
      getNextVersionCommand{ 'jx-release-version' }
    }
    infraConfig.use {
      dockerConfig.use {
        script.call(testImageName)
      }
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the static files read as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/pod-template.yml'))

    // And the make target called as shell steps
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make build'))
    assertTrue(assertMethodCallContainsPattern('sh','make test'))

    // With the deploy step called for latest
    assertFalse(assertMethodCallContainsPattern('echo','Skipping stage Deploy'))
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=jenkinsciinfra/deathstar:latest make deploy'))

    // And the img login and logout methods
    assertTrue(assertMethodCallContainsPattern('sh','img login'))
    assertTrue(assertMethodCallContainsPattern('sh','img logout'))

    // And generated reports are recorded
    assertTrue(assertMethodCallContainsPattern('recordIssues', '{enabledForFailure=true, tool=hadolint.json}'))

    // And all mocked/stubbed methods have to be called
    infraConfig.expect.verify()
    dockerConfig.expect.verify()
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
      getMainBranch{ 'master' }
      getAutomaticSemanticVersioning{ true }
      getNextVersionCommand{ 'jx-release-version' }
      getMetadataFromSh{ '' }
      getGitCredentials{ 'git-credentials' }
      getFullImageName { 'jenkinsciinfra/deathstar' }
    }
    infraConfig.use {
      dockerConfig.use {
        script.call(testImageName)
      }
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the static files read as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/pod-template.yml'))

    // And the make target called as shell steps
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make build'))
    assertTrue(assertMethodCallContainsPattern('sh','make test'))

    // With the tag step called for latest
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=jenkinsciinfra/deathstar:latest make deploy'))

    // Tag at the correct version
    assertTrue(assertMethodCallContainsPattern('echo','Configuring credential.helper'))
    assertTrue(assertMethodCallContainsPattern('echo','Tagging New Version: 1.0.1'))
    assertTrue(assertMethodCallContainsPattern('sh','git tag 1.0.1'))
    assertTrue(assertMethodCallContainsPattern('echo','Pushing Tag'))
    assertTrue(assertMethodCallContainsPattern('sh','git push origin --tags'))

    // And the img login and logout methods
    assertTrue(assertMethodCallContainsPattern('sh','img login'))
    assertTrue(assertMethodCallContainsPattern('sh','img logout'))

    // And generated reports are recorded
    assertTrue(assertMethodCallContainsPattern('recordIssues', '{enabledForFailure=true, tool=hadolint.json}'))

    // And all mocked/stubbed methods have to be called
    infraConfig.expect.verify()
    dockerConfig.expect.verify()
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
      getMainBranch{ 'master' }
      getAutomaticSemanticVersioning{ true }
      getMetadataFromSh{ metadataSh }
      getMetadataFromSh{ metadataSh }
      getGitCredentials{ 'git-credentials' }
      getFullImageName { 'jenkinsciinfra/deathstar' }
      getNextVersionCommand{ 'jx-release-version' }
    }
    infraConfig.use {
      dockerConfig.use {
        script.call(testImageName)
      }
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the static files read as expected
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/Makefile'))
    assertTrue(assertMethodCallContainsPattern('libraryResource','io/jenkins/infra/docker/pod-template.yml'))

    // And the make target called as shell steps
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertTrue(assertMethodCallContainsPattern('sh','make build'))
    assertTrue(assertMethodCallContainsPattern('sh','make test'))

    // With the tag step called for latest
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=jenkinsciinfra/deathstar:latest make deploy'))

    // Tag with the correct version and metadata
    assertTrue(assertMethodCallContainsPattern('echo','Configuring credential.helper'))
    assertTrue(assertMethodCallContainsPattern('echo','Tagging New Version: 1.0.1+2.280'))
    assertTrue(assertMethodCallContainsPattern('sh','git tag 1.0.1+2.280'))
    assertTrue(assertMethodCallContainsPattern('echo','Pushing Tag'))
    assertTrue(assertMethodCallContainsPattern('sh','git push origin --tags'))

    // And the img login and logout methods
    assertTrue(assertMethodCallContainsPattern('sh','img login'))
    assertTrue(assertMethodCallContainsPattern('sh','img logout'))

    // And generated reports are recorded
    assertTrue(assertMethodCallContainsPattern('recordIssues', '{enabledForFailure=true, tool=hadolint.json}'))

    // And all mocked/stubbed methods have to be called
    infraConfig.expect.verify()
    dockerConfig.expect.verify()
  }

  @Test
  void itBuildsAndDeploysImageWithCustomConfig() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with a custom configuration but not on the principal branch
    addEnvVar('BRANCH_NAME', 'main')

    dockerConfig.demand.with {
      getMainBranch{ 'main' }
      getMainBranch{ 'main' }
      getMainBranch{ 'main' }
      getMetadataFromSh{ '' }
      getFullImageName { 'registry.company.com/deathstar' }
      getAutomaticSemanticVersioning{ false }
      getNextVersionCommand{ 'jx-release-version' }
    }
    infraConfig.use {
      dockerConfig.use {
        script.call(testImageName, [
          registry: 'registry.company.com',
          dockerfile: 'build.Dockerfile',
          credentials: 'company-docker-registry-credz',
          mainBranch: 'main'
        ])
      }
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the deploy step called for latest
    assertFalse(assertMethodCallContainsPattern('echo','Skipping stage Deploy'))
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=registry.company.com/deathstar:latest make deploy'))

    // And all mocked/stubbed methods have to be called
    infraConfig.expect.verify()
    dockerConfig.expect.verify()
  }

  @Test
  void itBuildsAndDeploysImageWithCustomConfigAndTrailingSlashOnRegistry() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with a custom configuration but not on the principal branch
    addEnvVar('BRANCH_NAME', 'main')
    dockerConfig.demand.with {
      getMainBranch{ 'main' }
      getMainBranch{ 'main' }
      getMainBranch{ 'main' }
      getMetadataFromSh{ '' }
      getFullImageName { 'testregistry/deathstar' }
      getAutomaticSemanticVersioning{ false }
      getNextVersionCommand{ 'jx-release-version' }
    }
    infraConfig.use {
      dockerConfig.use {
        script.call(testImageName, [
                registry: 'testregistry/',
                dockerfile: 'build.Dockerfile',
                credentials: 'company-docker-registry-credz',
                mainBranch: 'main'
        ])
      }
    }
    printCallStack()

    // Then we expect a successful build with the code cloned
    assertJobStatusSuccess()

    // With the deploy step called for latest
    assertFalse(assertMethodCallContainsPattern('echo','Skipping stage Deploy'))
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=testregistry/deathstar:latest make deploy'))

    // And all mocked/stubbed methods have to be called
    infraConfig.expect.verify()
    dockerConfig.expect.verify()
  }

  @Test
  void itDoesNotDeployWhenNotOnPrincipalBranch() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with a default configuration but not on the principal branch
    addEnvVar('BRANCH_NAME', 'dev')
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getMainBranch{ 'master' }
      getMainBranch{ 'master' }
      getAutomaticSemanticVersioning{ false }
    }
    infraConfig.use {
      dockerConfig.use {
        script.call(testImageName)
      }
    }
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With no deploy step called for latest
    assertTrue(assertMethodCallContainsPattern('echo','Skipping stage Deploy'))
    assertFalse(assertMethodCallContainsPattern('sh','make deploy'))

    // And all mocked/stubbed methods have to be called
    infraConfig.expect.verify()
    dockerConfig.expect.verify()
  }

  @Test
  void itDeployWithTagWhenTriggeredByTag() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with a default configuration, on the principal branch, triggered by a tag
    addEnvVar('BRANCH_NAME', 'master') // Required until https://github.com/jenkinsci/JenkinsPipelineUnit/issues/330 is fixed
    addEnvVar('TAG_NAME', '1.0.0')
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getMainBranch{ 'master' }
      getMainBranch{ 'master' }
      getFullImageName { 'registry.company.com/deathstar' }
      getAutomaticSemanticVersioning{ false }
      getNextVersionCommand{ 'jx-release-version' }
      getMetadataFromSh{ '' }
    }
    infraConfig.use {
      dockerConfig.use {
        script.call(testImageName)
      }
    }
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With no deploy step called for latest
    assertFalse(assertMethodCallContainsPattern('echo','Skipping stage Deploy'))
    assertTrue(assertMethodCallContainsPattern('sh','IMAGE_DEPLOY_NAME=registry.company.com/deathstar:1.0.0 make deploy'))

    // And all mocked/stubbed methods have to be called
    infraConfig.expect.verify()
    dockerConfig.expect.verify()
  }

  @Test
  void itSkipTestStageIfNoCSTFile() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image with a default configuration and no cst.yml file found
    helper.registerAllowedMethod('fileExists', [String.class], { s -> return !s.equals('cst.yml') })
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getMainBranch{ 'master' }
      getMainBranch{ 'master' }
      getAutomaticSemanticVersioning{ false }
    }
    infraConfig.use {
      dockerConfig.use {
        script.call(testImageName)
      }
    }
    printCallStack()

    // Then we expect a successful build
    assertJobStatusSuccess()

    // With no test stage
    assertFalse(assertMethodCallContainsPattern('sh','make test'))

    // And all mocked/stubbed methods have to be called
    infraConfig.expect.verify()
    dockerConfig.expect.verify()
  }

  @Test
  void itFailFastButRecordReportWhenLintFails() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image which fails to pass the lint stage
    helper.registerAllowedMethod("sh", [String.class], {cmd->
      if (cmd.contains('make lint')) {
        binding.getVariable('currentBuild').result = 'FAILURE'
      }
    })
    dockerConfig.demand.with {
      getMainBranch{ 'dev' }
      getMainBranch{ 'dev' }
      getMainBranch{ 'dev' }
      getAutomaticSemanticVersioning{ false }
    }
    infraConfig.use {
      dockerConfig.use {
        script.call(testImageName)
      }
    }
    printCallStack()

    // Then we expect a failed build
    assertJobStatusFailure()

    // With a lint stage but no build stage
    assertTrue(assertMethodCallContainsPattern('sh','make lint'))
    assertFalse(assertMethodCallContainsPattern('sh','make build'))

    // And a lint report recorded
    assertTrue(assertMethodCallContainsPattern('recordIssues', '{enabledForFailure=true, tool=hadolint.json}'))

    // And all mocked/stubbed methods have to be called
    infraConfig.expect.verify()
    dockerConfig.expect.verify()
  }

  @Test
  void itFailFastWhenTestFails() throws Exception {
    def script = loadScript(scriptName)

    // when building a Docker Image which fails to pass the test stage on the master branch with default config
    addEnvVar('BRANCH_NAME', 'master')
    helper.registerAllowedMethod("sh", [String.class], {cmd->
      if (cmd.contains('make test')) {
        binding.getVariable('currentBuild').result = 'FAILURE'
      }
    })
    dockerConfig.demand.with {
      getMainBranch{ 'master' }
      getMainBranch{ 'master' }
      getMainBranch{ 'master' }
      getAutomaticSemanticVersioning{ false }
      getNextVersionCommand{ 'jx-release-version' }
      getMetadataFromSh{ '' }
    }
    infraConfig.use {
      dockerConfig.use {
        script.call(testImageName)
      }
    }
    printCallStack()

    // Then we expect a failed build
    assertJobStatusFailure()

    // With a test stage
    assertTrue(assertMethodCallContainsPattern('sh','make test'))

    // And no deploy stage as we expect test failure to fail the pipeline
    assertFalse(assertMethodCallContainsPattern('sh','make deploy'))

    // And all mocked/stubbed methods have to be called
    infraConfig.expect.verify()
    dockerConfig.expect.verify()
  }
}
