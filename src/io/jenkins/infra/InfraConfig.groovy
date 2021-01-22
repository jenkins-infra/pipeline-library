package io.jenkins.infra

class InfraConfig implements Serializable {
  String jenkinsURL

  public InfraConfig(Object env) {
    this.jenkinsURL = env?.JENKINS_URL ?: ""
  }

  Boolean isRunningOnJenkinsInfra() {
    return jenkinsURL == 'https://ci.jenkins.io/' || isTrusted() || isInfra()
  }

  Boolean isTrusted() {
    return jenkinsURL.startsWith('https://trusted.ci.jenkins.io')
  }

  Boolean isInfra() {
    return jenkinsURL.startsWith('https://infra.ci.jenkins.io')
  }

  String getDockerRegistry() {
    if (isTrusted() || isInfra()) {
      return 'jenkinsciinfra'
    } else {
      return 'jenkins4eval'
    }
  }
}
