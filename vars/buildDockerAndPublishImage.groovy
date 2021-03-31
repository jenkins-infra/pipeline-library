
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

  final String dockerImageName = dockerConfig.imageName
  final String dockerImageDir = dockerConfig.dockerImageDir

  podTemplate(
    inheritFrom: 'jnlp-linux',
    yaml: yamlPodDef,
  ) {
    node(POD_LABEL) {
      container('builder') {
        withEnv([
          "BUILD_DATE=${dockerConfig.buildDate}",
          "IMAGE_NAME=${dockerImageName}",
          "IMAGE_DIR=${dockerImageDir}",
          "IMAGE_DOCKERFILE=${dockerConfig.dockerfile}",
          "IMAGE_PLATFORM=${dockerConfig.platform}",
        ]) {
          stage("Prepare ${dockerImageName}") {
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
            stage("Get Next Version of ${dockerImageName}") {
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

          stage("Lint ${dockerImageName}") {
            def hadolintReport = "${env.WORKSPACE}/${dockerImageName}-hadolint.json"
            withEnv(["HADOLINT_REPORT=${hadolintReport}"]) {
              try {
                sh 'make lint'
              } finally {
                recordIssues(
                  enabledForFailure: true,
                  aggregatingResults: false,
                  tool: hadoLint(id: "hadolint-${dockerImageName.replaceAll('/','-')}", pattern: hadolintReport)
                )
              }
            }
          } // stage

          stage("Build ${dockerImageName}") {
            sh 'make build'
          } //stage

          // There can be 2 kind of tests: per image and per repository
          [
            "Image Test Harness": "${dockerImageDir}/cst.yml",
            "Common Test Harness": "${env.WORKSPACE}/common-cst.yml"
          ].each { testName, testHarness ->
            if (fileExists(testHarness)) {
              stage("Test ${testName} for ${dockerImageName}") {
                withEnv(["TEST_HARNESS=${testHarness}"]) {
                  sh 'make test'
                } // withEnv
              } //stage
            } // if
          }

          // Automatic tagging on principal branch is not enabled by default
          if (semVerEnabled) {
            stage("Semantic Release of ${dockerImageName}") {
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
            stage("Deploy ${dockerImageName}") {
              def imageDeployName = dockerConfig.getFullImageName()

              if(env.TAG_NAME) {
                // User could specify a tag in the image name. In that case the git tag is appended. Otherwise the docker tag is set to the git tag.
                if(imageDeployName.contains(':')) {
                  imageDeployName += "-${env.TAG_NAME}"
                } else {
                  imageDeployName += ":${env.TAG_NAME}"
                }
              }
              sh "IMAGE_DEPLOY_NAME=${imageDeployName} make deploy"
            } //stage
          } // if
        } // withEnv
      } // container
    } // node
  } // podTemplate
} // call
