
import io.jenkins.infra.DockerConfig
import io.jenkins.infra.InfraConfig
import java.util.Date

def call(String imageName, Map config=[:]) {

  // Initialize the groovy context
  final DockerConfig dockerConfig = new DockerConfig(imageName, new InfraConfig(env), config)

  // Retrieve Library's Static File Resources
  final String makefileContent = libraryResource 'io/jenkins/infra/docker/Makefile'
  final boolean semVerEnabled = dockerConfig.automaticSemanticVersioning && env.BRANCH_NAME == dockerConfig.mainBranch

  final String dockerImageName = dockerConfig.imageName
  final String dockerImageDir = dockerConfig.dockerImageDir

  podTemplate(
    annotations: [
      podAnnotation(key: 'container.apparmor.security.beta.kubernetes.io/builder', value: 'unconfined'),
      podAnnotation(key: 'container.seccomp.security.alpha.kubernetes.io/builder', value: 'unconfined'),
    ],
    containers: [
      containerTemplate(
        name: 'builder',
        image: config.builderImage ?: 'jenkinsciinfra/builder:1.2.7',
        command: 'cat',
        ttyEnabled: true,
        resourceRequestCpu: '2',
        resourceLimitCpu: '2',
        resourceRequestMemory: '2Gi',
        resourceLimitMemory: '2Gi',
      ),
      containerTemplate(
        name: 'next-version',
        image: config.nextVersionImage ?: 'ghcr.io/jenkins-x/jx-release-version:2.5.1',
        command: 'cat',
        ttyEnabled: true,
        resourceRequestCpu: '200m',
        resourceLimitCpu: '200m',
        resourceRequestMemory: '256Mi',
        resourceLimitMemory: '256Mi',
      ),
    ]
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
          boolean runStages = true
          stage("Check if ${dockerConfig.dockerfile} exists") {
            withCredentials([usernamePassword(credentialsId: dockerConfig.credentials, passwordVariable: 'DOCKER_REGISTRY_PSW', usernameVariable: 'DOCKER_REGISTRY_USR')]) {
              checkout scm
              if (!fileExists(dockerConfig.dockerfile)) {
                echo "WARNING: ${dockerConfig.dockerfile} file doesn't exist."
                runStages = false
              }
            }
          }
          stage("Prepare ${dockerImageName}") {
            if (runStages) {
              withCredentials([usernamePassword(credentialsId: dockerConfig.credentials, passwordVariable: 'DOCKER_REGISTRY_PSW', usernameVariable: 'DOCKER_REGISTRY_USR')]) {
                // Logging in on the Dockerhub helps to avoid request limit from DockerHub
                sh 'echo "${DOCKER_REGISTRY_PSW}" | img login -u "${DOCKER_REGISTRY_USR}" --password-stdin'

                // The makefile to use must come from the pipeline to avoid a nasty user trying to exfiltrate data from the build
                // Even though we have mitigation trhough the multibranch job config only allowed to build PRs from repo. contributors
                writeFile file: 'Makefile', text: makefileContent
              } // withCredentials
            } else {
              org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional("Prepare ${dockerImageName}") 
            }// if
          } // stage

          // Automatic tagging on principal branch is not enabled by default
          if (semVerEnabled) {
            stage("Get Next Version of ${dockerImageName}") {
              if (runStages) {
                container('next-version') {
                  nextVersion = sh(script:"${dockerConfig.nextVersionCommand}", returnStdout: true).trim()
                  if (dockerConfig.metadataFromSh != '') {
                    metadata = sh(script: "${dockerConfig.metadataFromSh}", returnStdout: true).trim()
                    nextVersion = nextVersion + metadata
                  } // if
                } // container
                echo "Next Release Version = $nextVersion"
              } else {
                org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional("Get Next Version of ${dockerImageName}") 
              } // if
            } // stage
          } // if

          stage("Lint ${dockerImageName}") {
            if (runStages) {
              // Define the image name as prefix to support multi images per pipeline
              def now = new Date()
              def hadolintReportId = "${dockerImageName}-hadolint-${now.getTime()}"
              def hadoLintReportFile = "${hadolintReportId}.json"
              withEnv(["HADOLINT_REPORT=${env.WORKSPACE}/${hadoLintReportFile}"]) {
                try {
                  sh 'make lint'
                } finally {
                  recordIssues(
                    enabledForFailure: true,
                    aggregatingResults: false,
                    tool: hadoLint(id: hadolintReportId, pattern: hadoLintReportFile)
                  )
                } // try
              } // withEnv
            } else {
              org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional("Lint ${dockerImageName}") 
            } // if
          } // stage

          stage("Build ${dockerImageName}") {
            if (runStages) {
              sh 'make build'
            } else {
              org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional("Build ${dockerImageName}") 
            } // if
          } //stage

          // There can be 2 kind of tests: per image and per repository
          [
            "Image Test Harness": "${dockerImageDir}/cst.yml",
            "Common Test Harness": "${env.WORKSPACE}/common-cst.yml"
          ].each { testName, testHarness ->
            if (fileExists(testHarness)) {
              stage("Test ${testName} for ${dockerImageName}") {
                if (runStages) {
                  withEnv(["TEST_HARNESS=${testHarness}"]) {
                    sh 'make test'
                  } // withEnv
                } else {
                  org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional("Test ${testName} for ${dockerImageName}") 
                } // if
              } //stage
            } // if
          }

          // Automatic tagging on principal branch is not enabled by default
          if (semVerEnabled) {
            stage("Semantic Release of ${dockerImageName}") {
              if (runStages) {
                echo "Configuring credential.helper"
                sh 'git config --local credential.helper "!f() { echo username=\\$GIT_USERNAME; echo password=\\$GIT_PASSWORD; }; f"'

                withCredentials([usernamePassword(credentialsId: "${dockerConfig.gitCredentials}", passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                  echo "Tagging New Version: $nextVersion"
                  sh "git tag $nextVersion"

                  echo "Pushing Tag"
                  sh "git push origin --tags"
                } // withCredentials
              } else {
                org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional("Semantic Release of ${dockerImageName}") 
              } // if
            } // stage
          } // if

          if (env.TAG_NAME || env.BRANCH_NAME == dockerConfig.mainBranch) {
            stage("Deploy ${dockerImageName}") {
              if (runStages) {
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
              } else {
                org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional("Deploy ${dockerImageName}") 
              } // if
            } // stage
          } // if

          if (env.TAG_NAME && dockerConfig.automaticSemanticVersioning) {
            stage("GitHub Release of ${dockerImageName}") {
              if (runStages) {
                withCredentials([usernamePassword(credentialsId: "${dockerConfig.gitCredentials}", passwordVariable: 'GITHUB_TOKEN', usernameVariable: 'GITHUB_USERNAME')]) {
                  String origin = sh(returnStdout: true, script: 'git remote -v | grep origin | grep push | sed \'s/^origin\\s//\' | sed \'s/\\s(push)//\'').trim() - '.git'
                  String org = origin.split('/')[3]
                  String repository = origin.split('/')[4]

                  try {
                      String release = sh(returnStdout: true, script: "gh api /repos/${org}/${repository}/releases | jq -e -r '.[] | select(.draft == true and .name == \"next\") | .id'").trim()
                      sh "gh api -X PATCH -F draft=false -F name=${env.TAG_NAME} -F tag_name=${env.TAG_NAME} /repos/${org}/${repository}/releases/$release"
                  } catch (err) {
                      echo "Release named 'next' does not exist"
                  }
                } // withCredentials
              } else {
                org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional("GitHub Release of ${dockerImageName}") 
              } // if
            } // stage
          } // if
        } // withEnv
      } // container
    } // node
  } // podTemplate
} // call
