package io.jenkins.infra
import java.net.URL;

class InfraConfig implements Serializable {
  String jenkinsURL
  String jenkinsHostname

  public InfraConfig(Object env) {
    this.jenkinsURL = env?.JENKINS_URL ?: ''
    if (this.jenkinsURL != '') {
      this.jenkinsHostname = new URL(this.jenkinsURL).getHost()
    } else {
      this.jenkinsHostname =  ''
    }
  }

  Boolean isJenkinsURLcontains(String search) {
    return jenkinsURL.startsWith('https://'+search+'.jenkins.io/') || jenkinsURL.startsWith('https://'+search+'.jenkins.io:')
  }

  Boolean isCI() {
    return isJenkinsURLcontains('ci')
  }

  Boolean isTrusted() {
    return isJenkinsURLcontains('trusted.ci')
  }

  Boolean isInfra() {
    return isJenkinsURLcontains('infra.ci')
  }

  Boolean isRunningOnJenkinsInfra() {
    return isCI() || isTrusted() || isInfra()
  }

  // Returns the Docker registry hostname which this instance has credentials for
  String getDockerRegistry() {
    if (isTrusted() || isInfra()) {
      return 'jenkinsciinfra'
    } else {
      return 'jenkins4eval'
    }
  }

  // Returns the Docker Informations for pulling images
  def getDockerPullOrgAndCredentialsId() {
    switch(jenkinsHostname){
      case "ci.jenkins.io": 
        return [error:false, organisation:"cijenkinsioinfra", credentialId:"cijenkinsioinfra-dockerhub-pull"]
        break;
      case "trusted.ci.jenkins.io": 
        return [error:false, organisation:"trustedcijenkinsio", credentialId:"trustedcijenkinsio-dockerhub-pull"]
        break;
      case "infra.ci.jenkins.io": 
        return [error:false, organisation:"infracijenkinsio", credentialId:"infracijenkinsio-dockerhub-pull"]
        break;
    }
    return [error:true, msg:"Cannot use Docker credentials outside of jenkins infra environment"]
  }

  // Returns the Docker Informations for pushing images
  def getDockerPushOrgAndCredentialsId() {
    switch(jenkinsHostname){
      case "ci.jenkins.io": 
        return [error:false, organisation:"jenkins4eval", credentialId:"jenkins4eval-dockerhub-push"]
        break;
      case "trusted.ci.jenkins.io": 
        return [error:false, organisation:"jenkins", credentialId:"jenkins-dockerhub-push"]
        break;
      case "infra.ci.jenkins.io": 
        return [error:false, organisation:"jenkinsciinfra", credentialId:"jenkinsciinfra-dockerhub-push"]
        break;
    }
    return [error:true, msg:"Cannot use Docker credentials outside of jenkins infra environment"]
  }
}
