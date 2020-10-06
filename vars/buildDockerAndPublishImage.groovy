import java.text.SimpleDateFormat;  
import java.util.Date;

def call(String imageName, Map config=[:]) {
  if (!config.registry) {
    if (infra.isTrusted() || infra.isInfra()) {
      config.registry = "jenkinsciinfra/"
    } else {
      config.registry = "jenkins4eval/"
    }
  }

  if (!config.dockerfile) {
    config.dockerfile = "Dockerfile"
  }

  if (!config.credentials) {
    config.credentials = "jenkins-dockerhub"
  }

  pipeline {
    agent {
      kubernetes {
        label 'build-publish-docker'
        inheritFrom 'jnlp-linux'
        yaml '''
apiVersion: "v1"
kind: "Pod"
metadata:
  labels:
    jenkins: "agent"
  annotations:
    container.apparmor.security.beta.kubernetes.io/img: unconfined
    container.seccomp.security.alpha.kubernetes.io/img: unconfined
spec:
  tolerations:
  - key: "os"
    operator: "Equal"
    value: "linux"
    effect: "NoSchedule"
  - key: "profile"
    operator: "Equal"
    value: "highmem"
    effect: "NoSchedule"
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
        - matchExpressions:
          - key: kubernetes.io/os
            operator: In
            values:
            - linux
      preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 1
        preference:
          matchExpressions:
          - key: agentpool
            operator: In
            values:
            - highmemlinux
  restartPolicy: "Never"
  containers:
    - name: img
      image: r.j3ss.co/img
      command:
      - cat
      tty: true
    - name: hadolint
      image: hadolint/hadolint
      command:
      - cat
      tty: true
        '''
      }
    }

    environment {
      BUILD_DATE = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date())
      IMAGE_NAME = "${config.registry}${imageName}"
      DOCKERFILE = "${config.dockerfile}"
    }

    options {
      disableConcurrentBuilds()
      buildDiscarder(logRotator(numToKeepStr: '5', artifactNumToKeepStr: '5'))
      timeout(time: 60, unit: "MINUTES")
      ansiColor("xterm")
    }

    stages {
      stage("Lint") {
        steps {
          container('hadolint') {
            script {
              writeFile(file: 'hadolint.json', text: sh(returnStdout: true, script: '/bin/hadolint --format json $DOCKERFILE || true').trim())
              recordIssues(tools: [hadoLint(pattern: 'hadolint.json')])
            }
          }
        }
      }
      stage("Build") {
        steps {
          container('img') {
            sh '''
                export GIT_COMMIT_REV=$(git log -n 1 --pretty=format:'%h')
                export GIT_SCM_URL=$(git remote show origin | grep 'Fetch URL' | awk '{print $3}')
                export SCM_URI=$(echo $GIT_SCM_URL | awk '{print gensub("git@github.com:","https://github.com/",$3)}')

                img build \
                    -t $IMAGE_NAME \
                    --build-arg "GIT_COMMIT_REV=$GIT_COMMIT_REV" \
                    --build-arg "GIT_SCM_URL=$GIT_SCM_URL" \
                    --build-arg "BUILD_DATE=$BUILD_DATE" \
                    --label "org.opencontainers.image.source=$GIT_SCM_URL" \
                    --label "org.label-schema.vcs-url=$GIT_SCM_URL" \
                    --label "org.opencontainers.image.url=$SCM_URI" \
                    --label "org.label-schema.url=$SCM_URI" \
                    --label "org.opencontainers.image.revision=$GIT_COMMIT_REV" \
                    --label "org.label-schema.vcs-ref=$GIT_COMMIT_REV" \
                    --label "org.opencontainers.image.created=$BUILD_DATE" \
                    --label "org.label-schema.build-date=$BUILD_DATE" \
                    -f $DOCKERFILE \
                    .
            '''
          }
        }
      }
      stage("Deploy master as latest") {
        when { branch "master" }
        steps {
          container('img') {
            script {
              sh "img tag ${config.registry}${imageName} ${config.registry}${imageName}:master"
              sh "img tag ${config.registry}${imageName} ${config.registry}${imageName}:${GIT_COMMIT}"
              withCredentials([usernamePassword(credentialsId: config.credentials, usernameVariable: 'DOCKER_USR', passwordVariable: 'DOCKER_PSW')]) {
                sh "echo $DOCKER_PSW | img login -u $DOCKER_USR --password-stdin"
              }
              sh "img push ${config.registry}${imageName}:master"
              sh "img push ${config.registry}${imageName}:${GIT_COMMIT}"
              sh "img push ${config.registry}${imageName}"
              sh "img logout"
              if (currentBuild.description) {
                currentBuild.description = currentBuild.description + " / "
              }
              currentBuild.description = "master / ${GIT_COMMIT}"
            }
          }
        }
      }
      stage("Deploy tag as tag") {
        when { buildingTag() }
        steps {
          container('img') {
            script {
              sh "img tag ${config.registry}${imageName} ${config.registry}${imageName}:${TAG_NAME}"
              withCredentials([usernamePassword(credentialsId: config.credentials, usernameVariable: 'DOCKER_USR', passwordVariable: 'DOCKER_PSW')]) {
                sh "echo $DOCKER_PSW | img login -u $DOCKER_USR --password-stdin"
              }
              sh "img push ${config.registry}${imageName}:${TAG_NAME}"
              sh "img logout"
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
}
