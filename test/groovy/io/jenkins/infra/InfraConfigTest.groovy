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
    assertTrue(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkins4eval', infraConfig.getDockerRegistry())
  }

  @Test
  void canHandleTrustedConfiguration() throws Exception {
    def infraConfig = new InfraConfig([JENKINS_URL: 'https://trusted.ci.jenkins.io/'])

    assertTrue(infraConfig.isTrusted())
    assertFalse(infraConfig.isInfra())
    assertTrue(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkinsciinfra', infraConfig.getDockerRegistry())
  }

  @Test
  void canHandleInfraConfiguration() throws Exception {
    def infraConfig = new InfraConfig([JENKINS_URL: 'https://infra.ci.jenkins.io/'])

    assertFalse(infraConfig.isTrusted())
    assertTrue(infraConfig.isInfra())
    assertTrue(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkinsciinfra', infraConfig.getDockerRegistry())
  }

  @Test
  void canHandleAnotherInstanceConfiguration() throws Exception {
    def infraConfig = new InfraConfig([JENKINS_URL: 'https://ci.quidditch.io'])

    assertFalse(infraConfig.isTrusted())
    assertFalse(infraConfig.isInfra())
    assertFalse(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkins4eval', infraConfig.getDockerRegistry())
  }

  @Test
  void canHandleDefaultConstructor() throws Exception {
    def infraConfig = new InfraConfig()

    assertFalse(infraConfig.isTrusted())
    assertFalse(infraConfig.isInfra())
    assertFalse(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkins4eval', infraConfig.getDockerRegistry())
  }

  void canHandleUnsetJenkinsUrl() throws Exception {
    def infraConfig = new InfraConfig([:])

    assertFalse(infraConfig.isTrusted())
    assertFalse(infraConfig.isInfra())
    assertFalse(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkins4eval', infraConfig.getDockerRegistry())
  }
}
