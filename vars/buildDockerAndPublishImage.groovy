import io.jenkins.infra.InfraConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.text.DateFormat

def call(String imageName, Map userConfig=[:]) {
  def defaultConfig = [
    useContainer: true, // Wether to use a container (with a container-less and root-less tool) or a VM (with a full-fledged Docker Engine) for executing the steps
    agentLabels: 'docker || linux-amd64-docker', // String expression for the labels the agent must match
    builderImage: 'jenkinsciinfra/builder:2.0.2', // Version managed by updatecli
    automaticSemanticVersioning: false, // Do not automagically increase semantic version by default
    dockerfile: 'Dockerfile', // Obvious default
    platform: 'linux/amd64', // Intel/AMD 64 Bits, following Docker platform identifiers
    nextVersionCommand: 'jx-release-version', // Commmand line used to retrieve the next version
    gitCredentials: '', // Credential ID for tagging and creating release
    imageDir: '.', // Relative path to the context directory for the Docker build
    credentials: 'jenkins-dockerhub',
  ]

  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  // Retrieve Library's Static File Resources
  final String makefileContent = libraryResource 'io/jenkins/infra/docker/Makefile'
  final boolean semVerEnabled = finalConfig.automaticSemanticVersioning && env.BRANCH_IS_PRIMARY

  final Date now = new Date()
  DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
  final String buildDate = dateFormat.format(now)

  def operatingSystem = finalConfig.platform.split('/')[0]
  def cpuArch = finalConfig.platform.split('/')[1]
  def cstConfigSuffix = ''
  if (finalConfig.agentLabels.contains('windows')) {
    operatingSystem = 'Windows'
    cstConfigSuffix = '-windows'
    cpuArch = 'x86_64' // hardcoded for Windows, we can't use `platform`as this docker parameter concerns only linux architectures
  }

  withContainerEngineAgent(finalConfig, {
    withEnv([
      "BUILD_DATE=${buildDate}",
      "IMAGE_NAME=${imageName}",
      "IMAGE_DIR=${finalConfig.imageDir}",
      "IMAGE_DOCKERFILE=${finalConfig.dockerfile}",
      "IMAGE_PLATFORM=${finalConfig.platform}",
      "PATH+BINS=${env.WORKSPACE}/.bin", // Add to the path the directory with the custom binaries that could be installed during the build
    ]) {
      stage("Prepare ${imageName}") {
        withCredentials([
          usernamePassword(credentialsId: finalConfig.credentials, passwordVariable: 'DOCKER_REGISTRY_PSW', usernameVariable: 'DOCKER_REGISTRY_USR')
        ]) {
          checkout scm

          // Logging in on the Dockerhub helps to avoid request limit from DockerHub
          if (operatingSystem == 'Windows') {
            powershell 'docker login -u "$env:DOCKER_REGISTRY_USR" -p "$env:DOCKER_REGISTRY_PSW"'// --password-stdin didn't worked on Windows
          } else {
            sh 'echo "${DOCKER_REGISTRY_PSW}" | "${CONTAINER_BIN}" login -u "${DOCKER_REGISTRY_USR}" --password-stdin'
          }

          // The makefile to use must come from the pipeline to avoid a nasty user trying to exfiltrate data from the build
          // Even though we have mitigation through the multibranch job config allowing to build PRs only from the repository contributors
          writeFile file: 'Makefile', text: makefileContent

        } // withCredentials
      } // stage

      // Automatic tagging on principal branch is not enabled by default
      if (semVerEnabled) {
        stage("Get Next Version of ${imageName}") {
          if (operatingSystem == 'Windows') {
            powershell 'git fetch --all --tags' // Ensure that all the tags are retrieved (uncoupling from job configuration, wether tags are fetched or not)
            nextVersion = powershell(script: finalConfig.nextVersionCommand, returnStdout: true).trim()
            echo "Next Release Version = $nextVersion"
          } else {
            sh 'git fetch --all --tags' // Ensure that all the tags are retrieved (uncoupling from job configuration, wether tags are fetched or not)
            nextVersion = sh(script: finalConfig.nextVersionCommand, returnStdout: true).trim()
            echo "Next Release Version = $nextVersion"
          }
        } // stage
      } // if

      stage("Lint ${imageName}") {
        // Define the image name as prefix to support multi images per pipeline
        def hadolintReportId = "${imageName.replaceAll(':','-')}-hadolint-${now.getTime()}"
        def hadoLintReportFile = "${hadolintReportId}.json"
        withEnv([
          "HADOLINT_REPORT=${env.WORKSPACE}/${hadoLintReportFile}",
        ]) {
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

      // There can be 2 kind of tests: per image and per repository.
      // Assuming Windows versions of cst configuration files finishing by "-windows" (e.g. "common-cst-windows.yml")
      [
        'Image Test Harness': "${finalConfig.imageDir}/cst${cstConfigSuffix}.yml",
        'Common Test Harness': "${env.WORKSPACE}/common-cst${cstConfigSuffix}.yml"
      ].each { testName, testHarness ->
        if (fileExists(testHarness)) {
          stage("Test ${testName} for ${imageName}") {
            withEnv([
              "TEST_HARNESS=${testHarness}",
            ]) {
              sh 'make test'
            } // withEnv
          } //stage
        } else {
          echo "Skipping test ${testName} for ${imageName} as ${testHarness} does not exist"
        }
      } // each

      // Automatic tagging on principal branch is not enabled by default
      if (semVerEnabled) {
        stage("Semantic Release of ${imageName}") {
          echo "Configuring credential.helper"
          if (operatingSystem == 'Windows') {
            powershell 'git config --local credential.helper "!f() { echo username=%GIT_USERNAME%; echo password=%GIT_PASSWORD% }; f"'
          } else {
            sh 'git config --local credential.helper "!f() { echo username=\\$GIT_USERNAME; echo password=\\$GIT_PASSWORD; }; f"'
          }

          withCredentials([
            usernamePassword(credentialsId: "${finalConfig.gitCredentials}", passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')
          ]) {
            withEnv(["NEXT_VERSION=${nextVersion}"]) {
              echo "Tagging and pushing the new version: $nextVersion"
              if (operatingSystem == 'Windows') {
                powershell '''
                git config user.name "$env:GIT_USERNAME"
                git config user.email "jenkins-infra@googlegroups.com"

                git tag -a "$env:NEXT_VERSION" -m "$env:IMAGE_NAME"
                git push origin --tags
                '''
              } else {
                sh '''
                git config user.name "${GIT_USERNAME}"
                git config user.email "jenkins-infra@googlegroups.com"

                git tag -a "${NEXT_VERSION}" -m "${IMAGE_NAME}"
                git push origin --tags
                '''
              } // if
            } // withEnv
          } // withCredentials
        } // stage
      } // if

      // if (env.TAG_NAME || env.BRANCH_IS_PRIMARY || debugDeploy) {
        stage("Deploy ${imageName}") {
          final InfraConfig infraConfig = new InfraConfig(env)
          String imageDeployName = infraConfig.dockerRegistry + '/' + imageName

          if (env.TAG_NAME) {
            // User could specify a tag in the image name. In that case the git tag is appended. Otherwise the docker tag is set to the git tag.
            if (imageDeployName.contains(':')) {
              imageDeployName += "-${env.TAG_NAME}"
            } else {
              imageDeployName += ":${env.TAG_NAME}"
            }
          }
          withEnv(["IMAGE_DEPLOY_NAME=${imageDeployName}"]) {
            // Please note that "make deploy" uses the environment variable "IMAGE_DEPLOY_NAME"
            sh 'make deploy'
          } // withEnv
        } //stage
      // } // if

      if (env.TAG_NAME && finalConfig.automaticSemanticVersioning) {
        stage('GitHub Release') {
          withCredentials([
            usernamePassword(credentialsId: "${finalConfig.gitCredentials}", passwordVariable: 'GITHUB_TOKEN', usernameVariable: 'GITHUB_USERNAME')
          ]) {
            if (operatingSystem == 'Windows') {
              final String origin = sh(returnStdout: true, script: 'git remote -v | grep origin | grep push | sed \'s/^origin\\s//\' | sed \'s/\\s(push)//\'').trim() - '.git'
            } else {
              final String origin = powershell(returnStdout: true, script: 'git remote get-url origin') - '.git'
            }
            final String org = origin.split('/')[3]
            final String repository = origin.split('/')[4]
            final String ghReleasesApiUri = "/repos/${org}/${repository}/releases"
            withEnv([
              "GH_RELEASES_API_URI=${ghReleasesApiUri}",
            ]) {
              if (operatingSystem == 'Windows') {
                final String release = powershell(returnStdout: true, script: 'gh api $env:GH_RELEASES_API_URI | jq -e -r \'[ .[] | select(.draft == true and .name == "next").id] | max\'').trim()
                withEnv(["GH_NEXT_RELEASE_URI=${ghReleasesApiUri}/${release}"]) {
                  sh 'gh api -X PATCH -F draft=false -F name="$env:TAG_NAME" -F tag_name="$env:TAG_NAME" "$env:GH_NEXT_RELEASE_URI"'
                } // withEnv
              } else {
                final String release = sh(returnStdout: true, script: 'gh api ${GH_RELEASES_API_URI} | jq -e -r \'[ .[] | select(.draft == true and .name == "next").id] | max\'').trim()
                withEnv(["GH_NEXT_RELEASE_URI=${ghReleasesApiUri}/${release}"]) {
                  sh 'gh api -X PATCH -F draft=false -F name="${TAG_NAME}" -F tag_name="${TAG_NAME}" "${GH_NEXT_RELEASE_URI}"'
                } // withEnv
              } // if
            } // withEnv
          } // withCredentials
        } // stage
      } // if

      // Logging out to ensure credentials are cleaned up if the current agent is reused
      if (operatingSystem == 'Windows') {
        powershell 'docker logout'
      } else {
        sh '"${CONTAINER_BIN}" logout'
      } // if
    } // withEnv
  }) // withContainerEngineAgent
} // call

// TODO: create withNativeShell with test on OS to call 'sh' or 'powershell'
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
            withEnv(['CONTAINER_BIN=img', 'CST_DRIVER=tar']) {
              body.call()
            }
          }
        }
  } else {
    node(finalConfig.agentLabels) {
      withEnv([
        'CONTAINER_BIN=docker',
        'CST_DRIVER=docker',
      ]) {
        body.call()
      }
    }
  }
}
