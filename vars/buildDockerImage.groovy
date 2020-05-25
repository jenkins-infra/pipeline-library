def call(String imageName, Map config=[:]) {
  if (!config.registry) {
    if (infra.isTrusted()) {
      config.registry = "jenkinsciinfra/"
    } else {
      config.registry = "jenkins4eval/"
    }
  }

  pipeline {
    agent {
      label 'docker&&linux'
    }

    options {
      disableConcurrentBuilds()
      buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
      timeout(time: 60, unit: "MINUTES")
      ansiColor("xterm")
    }

    stages {
      stage("Build") {
        steps {
          script {
            GIT_COMMIT_REV = sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
            GIT_SCM_URL = sh(returnStdout: true, script: "git remote show origin | grep 'Fetch URL' | awk '{print \$3}'").trim()
            SCM_URI = GIT_SCM_URL.replace("git@github.com:", "https://github.com/")
            BUILD_DATE = sh(returnStdout: true, script: "TZ=UTC date --rfc-3339=seconds | sed 's/ /T/'").trim()
          }
          sh """
            docker build \
              -t ${config.registry}${imageName} \
              --build-arg "GIT_COMMIT_REV=${GIT_COMMIT_REV}" \
              --build-arg "GIT_SCM_URL=${GIT_SCM_URL}" \
              --build-arg "BUILD_DATE=${BUILD_DATE}" \
              --label "org.opencontainers.image.source=${GIT_SCM_URL}" \
              --label "org.label-schema.vcs-url=${GIT_SCM_URL}" \
              --label "org.opencontainers.image.url==${SCM_URI}" \
              --label "org.label-schema.url=${SCM_URI}" \
              --label "org.opencontainers.image.revision=${GIT_COMMIT_REV}" \
              --label "org.label-schema.vcs-ref=${GIT_COMMIT_REV}" \
              --label "org.opencontainers.created=${BUILD_DATE}" \
              --label "org.label-schema.build-date=${BUILD_DATE}" \
              .
          """
        }
      }
      stage("Deploy master as latest") {
        when { branch "master" }
        steps {
          sh "docker tag ${config.registry}${imageName} ${config.registry}${imageName}:master"
          sh "docker tag ${config.registry}${imageName} ${config.registry}${imageName}:${GIT_COMMIT}"
          script {
            infra.withDockerCredentials {
              sh "docker push ${config.registry}${imageName}:master"
              sh "docker push ${config.registry}${imageName}:${GIT_COMMIT}"
              sh "docker push ${config.registry}${imageName}"
            }
            if (currentBuild.description) {
              currentBuild.description = currentBuild.description + " / "
            }
            currentBuild.description = "master / ${GIT_COMMIT}"
          }
        }
      }
      stage("Deploy tag as tag") {
        // semver regex from https://gist.github.com/jhorsman/62eeea161a13b80e39f5249281e17c39
        // when { tag pattern: "v([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+)?\$ ", comparator: "REGEXP"}
        // for now since testing only handles simple string, start witht that
        when { tag "v*" }
        steps {
          sh "docker tag ${config.registry}${imageName} ${config.registry}${imageName}:${TAG_NAME}"
          script {
            infra.withDockerCredentials {
              sh "docker push ${config.registry}${imageName}:${TAG_NAME}"
            }
            if (currentBuild.description) {
              currentBuild.description = currentBuild.description + " / "
            }
            currentBuild.description = "${TAG_NAME}"
          }
        }
      }
    }
  }
}
