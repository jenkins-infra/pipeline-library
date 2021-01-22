package io.jenkins.infra

import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

class DockerConfigTest {

    static String testImageName = "imagename"

    @Test
    void canHandleDefaultConfiguration() throws Exception {
        def infraConfig = new InfraConfig(['JENKINS_URL':'https://ci.jenkins.io/'])
        assertTrue(infraConfig.isRunningOnJenkinsInfra())
        assertFalse(infraConfig.isInfra())
        assertFalse(infraConfig.isTrusted())

        def dockerConfig = new DockerConfig(testImageName, [:], infraConfig)

        assertEquals( "jenkins4eval/imagename", dockerConfig.getFullImageName())
        assertEquals( "jenkins4eval", dockerConfig.getRegistry())
        assertEquals( "master", dockerConfig.mainBranch)
        assertEquals( "jenkins-dockerhub", dockerConfig.credentials)
        assertEquals( "Dockerfile", dockerConfig.dockerfile)
    }


    @Test
    void canHandleCustomConfiguration_WithTrailingSlashOnRegistry() throws Exception {
        def infraConfig = new InfraConfig(['JENKINS_URL':'https://ci.jenkins.io/'])
        assertTrue(infraConfig.isRunningOnJenkinsInfra())
        assertFalse(infraConfig.isInfra())
        assertFalse(infraConfig.isTrusted())

        def dockerConfig = new DockerConfig(testImageName, [
                registry: 'testregistry/',
                dockerfile: 'build.Dockerfile',
                credentials: 'company-docker-registry-credz',
                mainBranch: 'main'
        ], infraConfig)

        assertEquals( "testregistry/imagename", dockerConfig.getFullImageName())
        assertEquals( "testregistry", dockerConfig.getRegistry())
        assertEquals( "main", dockerConfig.mainBranch)
        assertEquals( "company-docker-registry-credz", dockerConfig.credentials)
        assertEquals( "build.Dockerfile", dockerConfig.dockerfile)
    }

    @Test
    void canHandleCustomConfiguration_OnInfra() throws Exception {
        def infraConfig = new InfraConfig(['JENKINS_URL':'https://infra.ci.jenkins.io/'])
        assertTrue(infraConfig.isRunningOnJenkinsInfra())
        assertTrue(infraConfig.isInfra())
        assertFalse(infraConfig.isTrusted())

        def dockerConfig = new DockerConfig(testImageName, [
                dockerfile: 'build.Dockerfile',
                credentials: 'company-docker-registry-credz',
                mainBranch: 'main'
        ], infraConfig)

        assertEquals( "jenkinsciinfra/imagename", dockerConfig.getFullImageName())
        assertEquals( "jenkinsciinfra", dockerConfig.getRegistry())
        assertEquals( "main", dockerConfig.mainBranch)
        assertEquals( "company-docker-registry-credz", dockerConfig.credentials)
        assertEquals( "build.Dockerfile", dockerConfig.dockerfile)
    }

    @Test
    void canHandleCustomConfiguration_OnTrusted() throws Exception {
        def infraConfig = new InfraConfig(['JENKINS_URL':'https://trusted.ci.jenkins.io/'])
        assertTrue(infraConfig.isRunningOnJenkinsInfra())
        assertFalse(infraConfig.isInfra())
        assertTrue(infraConfig.isTrusted())

        def dockerConfig = new DockerConfig(testImageName, [
                dockerfile: 'build.Dockerfile',
                credentials: 'company-docker-registry-credz',
                mainBranch: 'main'
        ], infraConfig)

        assertEquals( "jenkinsciinfra/imagename", dockerConfig.getFullImageName())
        assertEquals( "jenkinsciinfra", dockerConfig.getRegistry())
        assertEquals( "main", dockerConfig.mainBranch)
        assertEquals( "company-docker-registry-credz", dockerConfig.credentials)
        assertEquals( "build.Dockerfile", dockerConfig.dockerfile)
    }

}
