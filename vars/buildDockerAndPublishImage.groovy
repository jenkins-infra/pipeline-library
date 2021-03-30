
import io.jenkins.infra.DockerConfig
import io.jenkins.infra.InfraConfig

def call(String imageName, Map config=[:]) {

  // Initialize the groovy context
  final DockerConfig dockerConfig = new DockerConfig(imageName, new InfraConfig(env), config)

  // Retrieve Library's Static File Resources
  final String makefileContent = libraryResource 'io/jenkins/infra/docker/Makefile'
  final String podYamlTemplate = libraryResource 'io/jenkins/infra/docker/pod-template.yml'
  // Customize Pod label to improve build analysis
  final String yamlPodDef = podYamlTemplate.replaceAll('\\$IMAGE_NAME', imageName).replaceAll('\\$?\\{IMAGE_NAME\\}', imageName)

  final boolean semVerEnabled = dockerConfig.automaticSemanticVersioning && env.BRANCH_NAME == dockerConfig.mainBranch

  podTemplate(
    inheritFrom: 'jnlp-linux',
    yaml: yamlPodDef,
  ) {
    node(POD_LABEL) {
      container('builder') {
        withEnv([
          "BUILD_DATE=${dockerConfig.buildDate}",
          "IMAGE_NAME=${dockerConfig.imageName}",
          "DOCKERFILE=${dockerConfig.dockerfile}",
          "PLATFORM=${dockerConfig.platform}",
        ]) {
          stage('Prepare') {
            withCredentials([usernamePassword(credentialsId: dockerConfig.credentials, passwordVariable: 'DOCKER_REGISTRY_PSW', usernameVariable: 'DOCKER_REGISTRY_USR')]) {
              checkout scm

              // Logging in on the Dockerhub helps to avoid request limit from DockerHub
              sh 'echo "${DOCKER_REGISTRY_PSW}" | img login -u "${DOCKER_REGISTRY_USR}" --password-stdin'

              // The makefile to use must come from the pipeline to avoid a nasty user trying to exfiltrate data from the build
              // Even though we have mitigation trhough the multibranch job config only allowed to build PRs from repo. contributors
              writeFile file: 'Makefile', text: makefileContent
            } // withCredentials
          } // stage

          // Automatic tagging on principal branch is not enabled by default
          if (semVerEnabled) {
            stage('Next Version') {
              container('next-version') {
                nextVersion = sh(script:"${dockerConfig.nextVersionCommand}", returnStdout: true).trim()
                if (dockerConfig.metadataFromSh != '') {
                  metadata = sh(script: "${dockerConfig.metadataFromSh}", returnStdout: true).trim()
                  nextVersion = nextVersion + metadata
                } // if
              } // container
              echo "Next Release Version = $nextVersion"
            } // stage
          } // if

          stage("Lint") {
            try {
              sh 'make lint'
            } finally {
              recordIssues enabledForFailure: true, tool: hadoLint(pattern: 'hadolint.json')
            }
          } // stage

          stage("Build") {
            sh 'make build'
          } //stage

          // Test step is not mandatory as not all repositories
          if (fileExists('cst.yml')) {
            stage("Test") {
              sh 'make test'
            } //stage
          } // if

          // Automatic tagging on principal branch is not enabled by default
          if (semVerEnabled) {
            stage("Semantic Release") {
              echo "Configuring credential.helper"
              sh 'git config --local credential.helper "!f() { echo username=\\$GIT_USERNAME; echo password=\\$GIT_PASSWORD; }; f"'

              withCredentials([usernamePassword(credentialsId: "${dockerConfig.gitCredentials}", passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                echo "Tagging New Version: $nextVersion"
                sh "git tag $nextVersion"

                echo "Pushing Tag"
                sh "git push origin --tags"
              }
            } // stage
          } // if

          if (env.TAG_NAME || env.BRANCH_NAME == dockerConfig.mainBranch) {
            stage("Deploy") {
              def docker_image_tag = env.TAG_NAME ? env.TAG_NAME : 'latest'
              sh "IMAGE_DEPLOY_NAME=${dockerConfig.getFullImageName()}:${docker_image_tag} make deploy"
            } //stage
          } // if
        } // withEnv
      } // container
    } // node
  } // podTemplate
} // call
