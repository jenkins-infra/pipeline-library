import io.jenkins.infra.InfraConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.text.DateFormat

def call(String imageName, Map userConfig=[:]) {
  def defaultConfig = [
    useContainer: false, // Wether to use a container (with a container-less and root-less tool) or a VM (with a full-fledged Docker Engine) for executing the steps
    agentLabels: 'docker || linux-amd64-docker', // String expression for the labels the agent must match
    builderImage: 'jenkinsciinfra/builder:2.0.2', // Version managed by updatecli
    automaticSemanticVersioning: false, // Do not automagically increase semantic version by default
    includeImageNameInTag: false, // Set to true for multiple semversioned images built in parallel, will include the image name in tag to avoid conflict
    dockerfile: 'Dockerfile', // Obvious default
    platform: 'linux/amd64', // Intel/AMD 64 Bits, following Docker platform identifiers
    nextVersionCommand: 'jx-release-version', // Commmand line used to retrieve the next version
    gitCredentials: 'github-app-infra', // Credential ID for tagging and creating release
    imageDir: '.', // Relative path to the context directory for the Docker build
  ]

  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  // Retrieve Library's Static File Resources
  final String makefileContent = libraryResource 'io/jenkins/infra/docker/Makefile'
  final boolean semVerEnabledOnPrimaryBranch = finalConfig.automaticSemanticVersioning && env.BRANCH_IS_PRIMARY

  // Only run 1 build at a time on primary branch to ensure builds won't use the same tag when semantic versionning is activated
  if (env.BRANCH_IS_PRIMARY) {
    properties([disableConcurrentBuilds()])
  }

  final Date now = new Date()
  DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
  final String buildDate = dateFormat.format(now)

  // Warn about potential Linux/Windows contradictions between platform & agentLabels, and set the Windows config suffix for CST files
  String cstConfigSuffix = ''
  if (finalConfig.agentLabels.contains('windows') || finalConfig.platform.contains('windows')) {
    if (finalConfig.agentLabels.contains('windows') && !finalConfig.platform.contains('windows')) {
      echo "WARNING: A 'windows' agent is requested, but the 'platform' is set to '${finalConfig.platform}'."
    }
    if (!finalConfig.agentLabels.contains('windows') && finalConfig.platform.contains('windows')) {
      echo "WARNING: The 'platform' is set to '${finalConfig.platform}', but there isn't any 'windows' agent requested."
    }
    if (finalConfig.useContainer) {
      echo "WARNING: You're building a Windows image in a container, you should set 'useContainer' to 'false'."
    }
    cstConfigSuffix = '-windows'
  }
  String operatingSystem = finalConfig.platform.split('/')[0]

  withContainerEngineAgent(finalConfig, {
    withEnv([
      "BUILD_DATE=${buildDate}",
      "IMAGE_NAME=${imageName}",
      "IMAGE_DIR=${finalConfig.imageDir}",
      "IMAGE_DOCKERFILE=${finalConfig.dockerfile}",
      "IMAGE_PLATFORM=${finalConfig.platform}",
    ]) {
      infra.withDockerPullCredentials{
        stage("Prepare ${imageName}") {
          checkout scm

          // The makefile to use must come from the pipeline to avoid a nasty user trying to exfiltrate data from the build
          // Even though we have mitigation through the multibranch job config allowing to build PRs only from the repository contributors
          writeFile file: 'Makefile', text: makefileContent
        } // stage

        // Automatic tagging on principal branch is not enabled by default, show potential next version in PR anyway
        if (finalConfig.automaticSemanticVersioning) {
          stage("Get Next Version of ${imageName}") {
            imageInTag = '-' + imageName.replace('-','').replace(':','')
            if (isUnix()) {
              sh 'git fetch --all --tags' // Ensure that all the tags are retrieved (uncoupling from job configuration, wether tags are fetched or not)
              if (!finalConfig.includeImageNameInTag) {
                nextVersionPart = sh(script: "${finalConfig.nextVersionCommand} --previous-version=${currentVersion}", returnStdout: true).trim()
              } else {
                echo "Including the image name '${imageName}' in the next version"
                // Retrieving the semver part from the last tag of the current image
                currentSemVerVersionPart = sh(script: "git tag --list '*${imageInTag}' --sort=-v:refname | head -1", returnStdout: true).trim().replace(imageInTag, '')
                // Set a default value if there isn't yet any tag for the current image (https://groovy-lang.org/operators.html#_elvis_operator)
                currentSemVerVersionPart = currentSemVerVersionPart ?: '0.0.0'
                nextVersionSemVerPart = sh(script: "${finalConfig.nextVersionCommand} --previous-version=${currentVersion}", returnStdout: true).trim()
                nextVersion =  nextVersionSemVerPart + imageInTag
              }
            } else {
              powershell 'git fetch --all --tags' // Ensure that all the tags are retrieved (uncoupling from job configuration, wether tags are fetched or not)
              if (!finalConfig.includeImageNameInTag) {
                nextVersion = powershell(script: finalConfig.nextVersionCommand, returnStdout: true).trim()
              } else {
                echo "Including the image name '${imageName}' in the next version"
                // Retrieving the semver part from the last tag of the current image
                currentSemVerVersionPart = powershell(script: "git tag --list '*${imageInTag}' --sort=-v:refname | head -1", returnStdout: true).trim().replace(imageInTag, '')
                // Set a default value if there isn't yet any tag for the current image (https://groovy-lang.org/operators.html#_elvis_operator)
                currentSemVerVersionPart = currentSemVerVersionPart ?: '0.0.0'
                nextVersionSemVerPart = sh(script: "${finalConfig.nextVersionCommand} --previous-version=${currentSemVerVersionPart}", returnStdout: true).trim()
                nextVersion =  nextVersionSemVerPart + imageInTag
              }
            }
            echo "Next Release Version = $nextVersion"
          } // stage
        } // if

        stage("Lint ${imageName}") {
          // Define the image name as prefix to support multi images per pipeline
          def hadolintReportId = "${imageName.replaceAll(':','-')}-hadolint-${now.getTime()}"
          def hadoLintReportFile = "${hadolintReportId}.json"
          withEnv(["HADOLINT_REPORT=${env.WORKSPACE}/${hadoLintReportFile}"]) {
            try {
              if (isUnix()) {
                sh 'make lint'
              } else {
                powershell 'make lint'
              }
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
          if (isUnix()) {
            sh 'make build'
          } else {
            powershell 'make build'
          }
        } //stage

        // There can be 2 kind of tests: per image and per repository
        // Assuming Windows versions of cst configuration files finishing by "-windows" (e.g. "common-cst-windows.yml")
        [
          'Image Test Harness': "${finalConfig.imageDir}/cst${cstConfigSuffix}.yml",
          'Common Test Harness': "${env.WORKSPACE}/common-cst${cstConfigSuffix}.yml"
        ].each { testName, testHarness ->
          if (fileExists(testHarness)) {
            stage("Test ${testName} for ${imageName}") {
              withEnv(["TEST_HARNESS=${testHarness}"]) {
                if (isUnix()) {
                  sh 'make test'
                } else {
                  powershell 'make test'
                }
              } // withEnv
            } //stage
          } else {
            echo "Skipping test ${testName} for ${imageName} as ${testHarness} does not exist"
          } // if else
        } // each

        // Automatic tagging on principal branch is not enabled by default
        if (semVerEnabledOnPrimaryBranch) {
          stage("Semantic Release of ${imageName}") {
            echo "Configuring credential.helper"
            // The credential.helper will execute everything after the '!', here echoing the username, the password and an empty line to be passed to git as credentials when git needs it.
            if (isUnix()) {
              sh 'git config --local credential.helper "!set -u; echo username=\\$GIT_USERNAME && echo password=\\$GIT_PASSWORD && echo"'
            } else {
              // Using 'bat' here instead of 'powershell' to avoid variable interpolation problem with $
              bat 'git config --local credential.helper "!sh.exe -c \'set -u; echo username=$GIT_USERNAME && echo password=$GIT_PASSWORD && echo"\''
            }

            withCredentials([
              usernamePassword(credentialsId: "${finalConfig.gitCredentials}", passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')
            ]) {
              withEnv(["NEXT_VERSION=${nextVersion}"]) {
                echo "Tagging and pushing the new version: $nextVersion"
                if (isUnix()) {
                  sh '''
                  git config user.name "${GIT_USERNAME}"
                  git config user.email "jenkins-infra@googlegroups.com"

                  git tag -a "${NEXT_VERSION}" -m "${IMAGE_NAME}"
                  git push origin --tags
                  '''
                } else {
                  powershell '''
                  git config user.email "jenkins-infra@googlegroups.com"
                  git config user.password $env:GIT_PASSWORD

                  git tag -a "$env:NEXT_VERSION" -m "$env:IMAGE_NAME"
                  git push origin --tags
                  '''
                }
              } // withEnv
            } // withCredentials
          } // stage
        } // if
      }// withDockerPullCredentials
      infra.withDockerPushCredentials{
        if (env.TAG_NAME || env.BRANCH_IS_PRIMARY) {
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
              if (isUnix()) {
                sh 'make deploy'
              } else {
                powershell 'make deploy'
              }
            } // withEnv
          } //stage
        } // if
      } // withDockerPushCredentials


      if (env.TAG_NAME && finalConfig.automaticSemanticVersioning) {
        stage('GitHub Release') {
          withCredentials([
            usernamePassword(credentialsId: "${finalConfig.gitCredentials}", passwordVariable: 'GITHUB_TOKEN', usernameVariable: 'GITHUB_USERNAME')
          ]) {
            final String origin = sh(returnStdout: true, script: 'git remote get-url origin').trim() - '.git'
            final String org = origin.split('/')[3]
            final String repository = origin.split('/')[4]
            final String ghReleasesApiUri = "/repos/${org}/${repository}/releases"
            withEnv(["GH_RELEASES_API_URI=${ghReleasesApiUri}"]) {
              if (isUnix()) {
                final String release = sh(returnStdout: true, script: 'gh api ${GH_RELEASES_API_URI} | jq -e -r \'[ .[] | select(.draft == true and .name == "next").id] | max\'').trim()
              } else {
                final String release = powershell(returnStdout: true, script: 'gh api $env:GH_RELEASES_API_URI | jq -e -r \'[ .[] | select(.draft == true and .name == "next").id] | max\'').trim()
              }
              withEnv(["GH_NEXT_RELEASE_URI=${ghReleasesApiUri}/${release}"]) {
                if (isUnix()) {
                  sh 'gh api -X PATCH -F draft=false -F name="${TAG_NAME}" -F tag_name="${TAG_NAME}" "${GH_NEXT_RELEASE_URI}"'
                } else {
                  powershell 'gh api -X PATCH -F draft=false -F name="$env:TAG_NAME" -F tag_name="$env:TAG_NAME" "$env:GH_NEXT_RELEASE_URI"'
                }
              } // withEnv
            } // withEnv
          } // withCredentials
        } // stage
      } // if
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
            withEnv(['CONTAINER_BIN=img', 'CST_DRIVER=tar']) {
              body.call()
            }
          }
        }
  } else {
    node(finalConfig.agentLabels) {
      withEnv(['CONTAINER_BIN=docker', 'CST_DRIVER=docker',]) {
        body.call()
      }
    }
  }
}
