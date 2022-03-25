package io.jenkins.infra

import org.junit.Test

import groovy.mock.interceptor.StubFor

import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertEquals

class InfraConfigTest {

  @Test
  void canHandleCiConfiguration() throws Exception {
    def infraConfig = new InfraConfig([JENKINS_URL: 'https://ci.jenkins.io/'])

      assertFalse(infraConfig.isTrusted())
      assertFalse(infraConfig.isInfra())
      assertTrue(infraConfig.isCI())
      assertTrue(infraConfig.isRunningOnJenkinsInfra())
      assertEquals('jenkins4eval', infraConfig.getDockerRegistry())
      assertEquals('cijenkinsioinfra', infraConfig.getDockerPullOrgAndCredentialsId().organisation)
      assertEquals('cijenkinsioinfra-dockerhub-pull', infraConfig.getDockerPullOrgAndCredentialsId().credentialId)
      assertEquals('jenkins4eval', infraConfig.getDockerPushOrgAndCredentialsId().organisation)
      assertEquals('jenkins4eval-dockerhub-push', infraConfig.getDockerPushOrgAndCredentialsId().credentialId)
    }

  @Test
  void canHandleTrustedConfiguration() throws Exception {
    def infraConfig = new InfraConfig([JENKINS_URL: 'https://trusted.ci.jenkins.io/'])

      assertTrue(infraConfig.isTrusted())
      assertFalse(infraConfig.isInfra())
      assertFalse(infraConfig.isCI())
      assertTrue(infraConfig.isRunningOnJenkinsInfra())
      assertEquals('jenkinsciinfra', infraConfig.getDockerRegistry())
      assertEquals('trustedcijenkinsio', infraConfig.getDockerPullOrgAndCredentialsId().organisation)
      assertEquals('trustedcijenkinsio-dockerhub-pull', infraConfig.getDockerPullOrgAndCredentialsId().credentialId)
      assertEquals('jenkins', infraConfig.getDockerPushOrgAndCredentialsId().organisation)
      assertEquals('jenkins-dockerhub-push', infraConfig.getDockerPushOrgAndCredentialsId().credentialId)
    }

  @Test
  void canHandleInfraConfiguration() throws Exception {
    def infraConfig = new InfraConfig([JENKINS_URL: 'https://infra.ci.jenkins.io/'])

      assertFalse(infraConfig.isTrusted())
      assertTrue(infraConfig.isInfra())
      assertFalse(infraConfig.isCI())
      assertTrue(infraConfig.isRunningOnJenkinsInfra())
      assertEquals('jenkinsciinfra', infraConfig.getDockerRegistry())
      assertEquals('infracijenkinsio', infraConfig.getDockerPullOrgAndCredentialsId().organisation)
      assertEquals('infracijenkinsio-dockerhub-pull', infraConfig.getDockerPullOrgAndCredentialsId().credentialId)
      assertEquals('jenkinsciinfra', infraConfig.getDockerPushOrgAndCredentialsId().organisation)
      assertEquals('jenkinsciinfra-dockerhub-push', infraConfig.getDockerPushOrgAndCredentialsId().credentialId)
    }

  @Test
  void canHandleAnotherInstanceConfiguration() throws Exception {
    def infraConfig = new InfraConfig([JENKINS_URL: 'https://ci.quidditch.io'])

      assertFalse(infraConfig.isTrusted())
      assertFalse(infraConfig.isInfra())
      assertFalse(infraConfig.isCI())
      assertFalse(infraConfig.isRunningOnJenkinsInfra())
      assertEquals('jenkins4eval', infraConfig.getDockerRegistry())
      assertFalse(infraConfig.getDockerPullOrgAndCredentialsId())
      assertFalse(infraConfig.getDockerPushOrgAndCredentialsId())
    }

  @Test
  void canHandleDefaultConstructor() throws Exception {
    def infraConfig = new InfraConfig()

      assertFalse(infraConfig.isTrusted())
      assertFalse(infraConfig.isInfra())
      assertFalse(infraConfig.isCI())
      assertFalse(infraConfig.isRunningOnJenkinsInfra())
      assertEquals('jenkins4eval', infraConfig.getDockerRegistry())
      assertFalse(infraConfig.getDockerPullOrgAndCredentialsId())
      assertFalse(infraConfig.getDockerPushOrgAndCredentialsId())
    }

    @Test
    void canHandleUnsetJenkinsUrl() throws Exception {
      def infraConfig = new InfraConfig([:])

      assertFalse(infraConfig.isTrusted())
      assertFalse(infraConfig.isInfra())
      assertFalse(infraConfig.isCI())
      assertFalse(infraConfig.isRunningOnJenkinsInfra())
      assertEquals('jenkins4eval', infraConfig.getDockerRegistry())
      assertFalse(infraConfig.getDockerPullOrgAndCredentialsId())
      assertFalse(infraConfig.getDockerPushOrgAndCredentialsId())
    }

    assertFalse(infraConfig.isTrusted())
    assertFalse(infraConfig.isInfra())
    assertFalse(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkins4eval', infraConfig.getDockerRegistry())
  }
}
