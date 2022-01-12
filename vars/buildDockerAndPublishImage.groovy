
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

  // The podTemplate must define only a single container, named `jnlp`
  // Ref - https://support.cloudbees.com/hc/en-us/articles/360054642231-Considerations-for-Kubernetes-Clients-Connections-when-using-Kubernetes-Plugin
  podTemplate(
    annotations: [
      podAnnotation(key: 'container.apparmor.security.beta.kubernetes.io/jnlp', value: 'unconfined'),
      podAnnotation(key: 'container.seccomp.security.alpha.kubernetes.io/jnlp', value: 'unconfined'),
    ],
    containers: [
      // This container must be named `jnlp` and should use the default entrypoint/cmd (command/args) inherited from inbound-agent parent image
      containerTemplate(
        name: 'jnlp',
        image: config.builderImage ?: 'jenkinsciinfra/builder:2.0.7',
        resourceRequestCpu: '2',
        resourceLimitCpu: '2',
        resourceRequestMemory: '2Gi',
        resourceLimitMemory: '2Gi',
      ),
    ]
  ) {
    node(POD_LABEL) {
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
            nextVersion = sh(script:"${dockerConfig.nextVersionCommand}", returnStdout: true).trim()
            if (dockerConfig.metadataFromSh != '') {
              metadata = sh(script: "${dockerConfig.metadataFromSh}", returnStdout: true).trim()
              nextVersion = nextVersion + metadata
            } // if
            echo "Next Release Version = $nextVersion"
          } // stage
        } // if

        stage("Lint ${dockerImageName}") {
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

        if (env.TAG_NAME && dockerConfig.automaticSemanticVersioning) {
          stage("GitHub Release") {
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
          } // stage
        } // if
      } // withEnv
    } // node
  } // podTemplate
} // call
