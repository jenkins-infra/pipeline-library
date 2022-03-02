import io.jenkins.infra.InfraConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.text.DateFormat

def call(String imageName, Map userConfig=[:]) {
  def defaultConfig = [
    useContainer: true, // Wether to use a container (with a container-less and root-less tool) or a VM (with a full-fledge Docker Engine) for executing the steps
    agentLabels: 'docker', // String expression for the labels that the agent must match
    builderImage: 'jenkinsciinfra/builder:2.0.2', // Version managed by updatecli
    automaticSemanticVersioning: false, // Do not automagically increase semantic version by default
    dockerfile: 'Dockerfile', // Obvious default
    platform: 'linux/amd64', // Intel/AMD 64 Bits, following Docker platform identifiers
    nextVersionCommand: 'jx-release-version', // Commmand line used to retrieve the next version
    gitCredentials: '', // Credential ID for tagging and creating release
    imageDir: '.', // Relative path to the context directory for the Docker build
    credentials: 'jenkins-dockerhub'
  ]

  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  // Retrieve Library's Static File Resources
  final String makefileContent = libraryResource 'io/jenkins/infra/docker/Makefile'
  final boolean semVerEnabled = finalConfig.automaticSemanticVersioning && env.BRANCH_IS_PRIMARY

  final Date now = new Date()
  DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
  final String buildDate = dateFormat.format(now)

  withContainerEngineAgent(finalConfig, {
    withEnv([
      "BUILD_DATE=${buildDate}",
      "IMAGE_NAME=${imageName}",
      "IMAGE_DIR=${finalConfig.imageDir}",
      "IMAGE_DOCKERFILE=${finalConfig.dockerfile}",
      "IMAGE_PLATFORM=${finalConfig.platform}",
    ]) {
      stage("Prepare ${imageName}") {
        withCredentials([usernamePassword(credentialsId: finalConfig.credentials, passwordVariable: 'DOCKER_REGISTRY_PSW', usernameVariable: 'DOCKER_REGISTRY_USR')]) {
          checkout scm

          // Logging in on the Dockerhub helps to avoid request limit from DockerHub
          sh 'echo "${DOCKER_REGISTRY_PSW}" | "${CONTAINER_BIN}" login -u "${DOCKER_REGISTRY_USR}" --password-stdin'

          // The makefile to use must come from the pipeline to avoid a nasty user trying to exfiltrate data from the build
          // Even though we have mitigation trhough the multibranch job config only allowed to build PRs from repo. contributors
          writeFile file: 'Makefile', text: makefileContent
        } // withCredentials
      } // stage

      // Automatic tagging on principal branch is not enabled by default
      if (semVerEnabled) {
        stage("Get Next Version of ${imageName}") {
          nextVersion = sh(script:"${finalConfig.nextVersionCommand}", returnStdout: true).trim()
          echo "Next Release Version = $nextVersion"
        } // stage
      } // if

      stage("Lint ${imageName}") {
        // Define the image name as prefix to support multi images per pipeline
        def hadolintReportId = "${imageName.replaceAll(':','-')}-hadolint-${now.getTime()}"
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

      stage("Build ${imageName}") {
        sh 'make build'
      } //stage

      // There can be 2 kind of tests: per image and per repository
      [
        "Image Test Harness": "${finalConfig.imageDir}/cst.yml",
        "Common Test Harness": "${env.WORKSPACE}/common-cst.yml"
      ].each { testName, testHarness ->
        if (fileExists(testHarness)) {
          stage("Test ${testName} for ${imageName}") {
            withEnv(["TEST_HARNESS=${testHarness}"]) {
              sh 'make test'
            } // withEnv
          } //stage
        } // if
      }

      // Automatic tagging on principal branch is not enabled by default
      if (semVerEnabled) {
        stage("Semantic Release of ${imageName}") {
          echo "Configuring credential.helper"
          sh 'git config --local credential.helper "!f() { echo username=\\$GIT_USERNAME; echo password=\\$GIT_PASSWORD; }; f"'

          withCredentials([usernamePassword(credentialsId: "${finalConfig.gitCredentials}", passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
            echo "Tagging New Version: $nextVersion"
            sh "git tag $nextVersion"

            echo "Pushing Tag"
            sh "git push origin --tags"
          }
        } // stage
      } // if

      if (env.TAG_NAME || env.BRANCH_IS_PRIMARY) {
        stage("Deploy ${imageName}") {
          final InfraConfig infraConfig = new InfraConfig(env)
          String imageDeployName = infraConfig.dockerRegistry + '/' + imageName

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

      if (env.TAG_NAME && finalConfig.automaticSemanticVersioning) {
        stage("GitHub Release") {
          withCredentials([usernamePassword(credentialsId: "${finalConfig.gitCredentials}", passwordVariable: 'GITHUB_TOKEN', usernameVariable: 'GITHUB_USERNAME')]) {
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

      // Logging out to ensure that credentials are cleanup up if the current agent is reused
      sh '"${CONTAINER_BIN}" logout'
    } // withEnv
  }) // withContainerEngineAgent
} // call

def withContainerEngineAgent(finalConfig, body) {
  if (finalConfig.useContainer) {
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
          image: finalConfig.builderImage,
          resourceRequestCpu: '2',
          resourceLimitCpu: '2',
          resourceRequestMemory: '2Gi',
          resourceLimitMemory: '2Gi',
        ),
      ]
    ) {
      node(POD_LABEL) {
        withEnv(['CONTAINER_BIN=img']) {
          body.call()
        }
      }
    }
  } else {
    node(finalConfig.agentLabels) {
      withEnv([
        'CONTAINER_BIN=docker',
        'HADOLINT_BIN=docker run --rm -v "$(pwd):$(pwd)" -w "$(pwd)" hadolint/hadolint hadolint',
        ]) {
        body.call()
      }
    }
  }
}
