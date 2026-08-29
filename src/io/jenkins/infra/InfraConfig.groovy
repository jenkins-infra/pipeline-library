package io.jenkins.infra
import java.net.URL

class InfraConfig implements Serializable {
  String jenkinsURL
  String jenkinsHostname

  public InfraConfig(Object env) {
    this.jenkinsURL = env?.JENKINS_URL ?: ''
    this.jenkinsHostname = ''
    if (this.jenkinsURL != '') {
      this.jenkinsHostname = new URL(this.jenkinsURL).getHost()
    }
  }

  Boolean isJenkinsURLcontains(String search) {
    return jenkinsURL.startsWith('https://' + search + '.jenkins.io/') || jenkinsURL.startsWith('https://' + search + '.jenkins.io:')
  }

  Boolean isCI() {
    return isJenkinsURLcontains('ci')
  }

  Boolean isTrusted() {
    return isJenkinsURLcontains('trusted.ci')
  }

  Boolean isRelease() {
    return isJenkinsURLcontains('release.ci')
  }

  Boolean isInfra() {
    return isJenkinsURLcontains('infra.ci')
  }

  Boolean isRunningOnJenkinsInfra() {
    return isCI() || isTrusted() || isRelease() || isInfra()
  }

  // Returns the Docker registry hostname which this instance has credentials for
  String getDockerRegistryNamespace() {
    if (isTrusted() || isInfra()) {
      return 'jenkinsciinfra'
    } else {
      return 'jenkins4eval'
    }
  }
}
