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
    assertFalse(infraConfig.isRelease())
    assertFalse(infraConfig.isInfra())
    assertTrue(infraConfig.isCI())
    assertTrue(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkins4eval', infraConfig.getDockerRegistryNamespace())
  }

  @Test
  void canHandleTrustedConfiguration() throws Exception {
    def infraConfig = new InfraConfig([JENKINS_URL: 'https://trusted.ci.jenkins.io/'])

    assertTrue(infraConfig.isTrusted())
    assertFalse(infraConfig.isRelease())
    assertFalse(infraConfig.isInfra())
    assertFalse(infraConfig.isCI())
    assertTrue(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkinsciinfra', infraConfig.getDockerRegistryNamespace())
  }

  @Test
  void canHandleReleaseConfiguration() throws Exception {
    def infraConfig = new InfraConfig([JENKINS_URL: 'https://release.ci.jenkins.io/'])

    assertFalse(infraConfig.isTrusted())
    assertTrue(infraConfig.isRelease())
    assertFalse(infraConfig.isInfra())
    assertFalse(infraConfig.isCI())
    assertTrue(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkins4eval', infraConfig.getDockerRegistryNamespace())
  }

  @Test
  void canHandleInfraConfiguration() throws Exception {
    def infraConfig = new InfraConfig([JENKINS_URL: 'https://infra.ci.jenkins.io/'])

    assertFalse(infraConfig.isTrusted())
    assertFalse(infraConfig.isRelease())
    assertTrue(infraConfig.isInfra())
    assertFalse(infraConfig.isCI())
    assertTrue(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkinsciinfra', infraConfig.getDockerRegistryNamespace())
  }

  @Test
  void canHandleAnotherInstanceConfiguration() throws Exception {
    def infraConfig = new InfraConfig([JENKINS_URL: 'https://ci.quidditch.io'])

    assertFalse(infraConfig.isTrusted())
    assertFalse(infraConfig.isRelease())
    assertFalse(infraConfig.isInfra())
    assertFalse(infraConfig.isCI())
    assertFalse(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkins4eval', infraConfig.getDockerRegistryNamespace())
  }

  @Test
  void canHandleDefaultConstructor() throws Exception {
    def infraConfig = new InfraConfig()

    assertFalse(infraConfig.isTrusted())
    assertFalse(infraConfig.isRelease())
    assertFalse(infraConfig.isInfra())
    assertFalse(infraConfig.isCI())
    assertFalse(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkins4eval', infraConfig.getDockerRegistryNamespace())
  }

  @Test
  void canHandleUnsetJenkinsUrl() throws Exception {
    def infraConfig = new InfraConfig([:])

    assertFalse(infraConfig.isTrusted())
    assertFalse(infraConfig.isRelease())
    assertFalse(infraConfig.isInfra())
    assertFalse(infraConfig.isCI())
    assertFalse(infraConfig.isRunningOnJenkinsInfra())
    assertEquals('jenkins4eval', infraConfig.getDockerRegistryNamespace())
  }
}
