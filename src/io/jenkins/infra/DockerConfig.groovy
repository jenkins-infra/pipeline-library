package io.jenkins.infra


import java.text.SimpleDateFormat
import java.util.Date

class DockerConfig {
  String imageName

  String registry

  String dockerfile

  String credentials

  String mainBranch

  def buildDate

  def infraConfig

  public DockerConfig(String imageName, Map config=[:], InfraConfig infraConfig) {
    this.imageName = imageName

    this.infraConfig = infraConfig

    this.registry = config.registry

    this.dockerfile = config.dockerfile
    if (!config.dockerfile) {
      this.dockerfile = "Dockerfile"
    }

    this.credentials = config.credentials
    if (!config.credentials) {
      this.credentials = "jenkins-dockerhub"
    }

    this.mainBranch = config.mainBranch
    if (!config.mainBranch) {
      this.mainBranch = "master"
    }

    this.buildDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date())
  }

  String getFullImageName() {
    return getRegistry() + "/" + imageName
  }

  // Custom getter to avoid declaring NonCPS method called from constructor
  String getRegistry() {
    registry ?: infraConfig?.dockerRegistry ?: 'noregistry'
  }
}
