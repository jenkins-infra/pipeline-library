package io.jenkins.infra

import org.junit.Test

import groovy.mock.interceptor.StubFor

import static org.junit.Assert.assertEquals

class DockerConfigTest {

    static String testImageName = "imagename"

    @Test
    void canHandleDefaultConfiguration() throws Exception {
        String sutRegistry = 'hogwarts'
        def dockerConfig
        def infraConfig = new StubFor(InfraConfig.class)
        infraConfig.demand.with {
          getDockerRegistry{ sutRegistry }
          getDockerRegistry{ sutRegistry }
        }
        infraConfig.use {
          dockerConfig = new DockerConfig(testImageName, new InfraConfig())
        }

        assertEquals( sutRegistry + '/imagename', dockerConfig.getFullImageName())
        assertEquals( sutRegistry, dockerConfig.getRegistry())
        assertEquals( 'master', dockerConfig.mainBranch)
        assertEquals( 'jenkins-dockerhub', dockerConfig.credentials)
        assertEquals( 'Dockerfile', dockerConfig.dockerfile)
        assertEquals( '.', dockerConfig.getDockerImageDir())
    }


    @Test
    void canHandleCustomConfiguration_WithTrailingSlashOnRegistry() throws Exception {
        def dockerConfig
        def infraConfig = new StubFor(InfraConfig.class)

        infraConfig.use {
          dockerConfig = new DockerConfig(testImageName, new InfraConfig(), [
            registry: 'testregistry/',
            dockerfile: 'build.Dockerfile',
            credentials: 'company-docker-registry-credz',
            mainBranch: 'main'
          ])
        }

        assertEquals( 'testregistry/imagename', dockerConfig.getFullImageName())
        assertEquals( 'testregistry', dockerConfig.getRegistry())
        assertEquals( 'main', dockerConfig.mainBranch)
        assertEquals( 'company-docker-registry-credz', dockerConfig.credentials)
        assertEquals( 'build.Dockerfile', dockerConfig.dockerfile)
    }

    @Test
    void canHandleCustomConfiguration_OnInfra() throws Exception {
        String sutRegistry = 'london'
        def dockerConfig
        def infraConfig = new StubFor(InfraConfig.class)
        infraConfig.demand.with {
          getDockerRegistry{ sutRegistry }
          getDockerRegistry{ sutRegistry }
        }

        infraConfig.use {
          dockerConfig = new DockerConfig(testImageName, new InfraConfig(), [
            dockerfile: 'build.Dockerfile',
            credentials: 'company-docker-registry-credz',
            mainBranch: 'main',
            imageDir: 'docker/'
          ])
        }

        assertEquals( sutRegistry + '/imagename', dockerConfig.getFullImageName())
        assertEquals( sutRegistry, dockerConfig.getRegistry())
        assertEquals( 'main', dockerConfig.mainBranch)
        assertEquals( 'company-docker-registry-credz', dockerConfig.credentials)
        assertEquals( 'build.Dockerfile', dockerConfig.dockerfile)
        assertEquals( 'docker/', dockerConfig.getDockerImageDir())
    }

}
