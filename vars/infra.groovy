#!/usr/bin/env groovy

Boolean isRunningOnJenkinsInfra() {
    return env.JENKINS_URL == 'https://ci.jenkins.io/' || isTrusted()
}

Boolean isTrusted() {
    return env.JENKINS_URL == 'https://trusted.ci.jenkins.io:1443/'
}

Object withDockerCredentials(Closure body) {
    if (isTrusted()) {
        withCredentials([[$class: 'ZipFileBinding', credentialsId: 'jenkins-dockerhub', variable: 'DOCKER_CONFIG']]) {
            return body.call()
        }
    }
    else {
        echo 'Cannot use Docker credentials outside of the trusted environment'
    }
}
