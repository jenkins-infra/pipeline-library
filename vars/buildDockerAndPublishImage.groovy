import io.jenkins.infra.InfraConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.text.DateFormat

// makecall is a function to concentrate all the call to 'make'
def makecall(String action, String imageDeployName, String targetOperationSystem, String specificDockerBakeFile, String dockerBakeTarget) {
  final String bakefileContent = libraryResource 'io/jenkins/infra/docker/jenkinsinfrabakefile.hcl'
  // Please note that "make deploy" and the generated bake deploy file uses the environment variable "IMAGE_DEPLOY_NAME"
  if (isUnix()) {
    if (! specificDockerBakeFile) {
      specificDockerBakeFile = 'jenkinsinfrabakefile.hcl'
      writeFile file: specificDockerBakeFile, text: bakefileContent
    }
    withEnv([
      "DOCKER_BAKE_FILE=${specificDockerBakeFile}",
      "DOCKER_BAKE_TARGET=${dockerBakeTarget}",
      "IMAGE_DEPLOY_NAME=${imageDeployName}"
    ]) {
      sh 'export BUILDX_BUILDER_NAME=buildx-builder; docker buildx use "${BUILDX_BUILDER_NAME}" 2>/dev/null || docker buildx create --use --name="${BUILDX_BUILDER_NAME}"'
      sh "make bake-$action"
    }
  } else {
    if (action == 'deploy') {
      if (env.TAG_NAME) {
        // User could specify a tag in the image name. In that case the git tag is appended. Otherwise the docker tag is set to the git tag.
        if (imageDeployName.contains(':')) {
          imageDeployName += "-${env.TAG_NAME}"
        } else {
          imageDeployName += ":${env.TAG_NAME}"
        }
      }
    }
    withEnv(["IMAGE_DEPLOY_NAME=${imageDeployName}"]) {
      powershell "make $action"
    } // withEnv
  } // unix agent
}

def call(String imageShortName, Map userConfig=[:]) {
  def defaultConfig = [
    agentLabels: 'docker || linux-amd64-docker', // String expression for the labels the agent must match
    automaticSemanticVersioning: false, // Do not automagically increase semantic version by default
    dockerfile: 'Dockerfile', // Obvious default
    targetplatforms: '', // // Define the (comma separated) list of Docker supported platforms to build the image for. Defaults to `linux/amd64` when unspecified. Incompatible with the legacy `platform` attribute.
    nextVersionCommand: 'jx-release-version', // Commmand line used to retrieve the next version
    gitCredentials: 'github-app-infra.ci.jenkins.io-docker-deploy', // Credential ID for tagging and creating release
    imageDir: '.', // Relative path to the context directory for the Docker build
    registryNamespace: '', // Empty by default (means "autodiscover based on the current controller")
    unstash: '', // Allow to unstash files if not empty
    dockerBakeFile: '', // Specify the path to a custom Docker Bake file to use instead of the default one
    dockerBakeTarget: 'default', // Specify the target of a custom Docker Bake file to work with
    disablePublication: false, // Allow to disable tagging and publication of container image and GitHub release (true by default)
  ]
  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  // Retrieve Library's Static File Resources
  final String makefileContent = libraryResource 'io/jenkins/infra/docker/Makefile'

  final boolean semVerEnabledOnPrimaryBranch = finalConfig.automaticSemanticVersioning && !finalConfig.disablePublication && env.BRANCH_IS_PRIMARY

  // Only run 1 build at a time on primary branch to ensure builds won't use the same tag when semantic versionning is activated
  if (env.BRANCH_IS_PRIMARY) {
    properties([disableConcurrentBuilds()])
  }

  final Date now = new Date()
  DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
  final String buildDate = dateFormat.format(now)

  if (finalConfig.platform) {
    if (finalConfig.targetplatforms) {
      // only one platform parameter is supported both platform and platforms cannot be set at the same time
      echo 'ERROR: Only one platform parameter is supported for now either platform or targetplatforms, prefer `targetplatforms`.'
      currentBuild.result = 'FAILURE'
      return
    }
    finalConfig.targetplatforms = finalConfig.platform
    echo "WARNING: `platform` is deprecated, use `targetplatforms` instead."
  }

  // Defaults to 'linux/amd64' if targetplatforms is not set
  if (finalConfig.targetplatforms == '') {
    finalConfig.targetplatforms = 'linux/amd64'
  }

  // Warn about potential Linux/Windows contradictions between platform & agentLabels, and set the Windows config suffix for CST files
  // for now only one platform possible per windows build !
  String cstConfigSuffix = ''
  if (finalConfig.agentLabels.contains('windows') || finalConfig.targetplatforms.contains('windows')) {
    if (finalConfig.targetplatforms.split(',').length > 1) {
      echo 'ERROR: with windows, only one platform can be specified within targetplatforms.'
      currentBuild.result = 'FAILURE'
      return
    }
    if (finalConfig.agentLabels.contains('windows') && !finalConfig.targetplatforms.contains('windows')) {
      echo "WARNING: A 'windows' agent is requested, but the 'platform(s)' is set to '${finalConfig.targetplatforms}'."
    }
    if (!finalConfig.agentLabels.contains('windows') && finalConfig.targetplatforms.contains('windows')) {
      echo "WARNING: The 'targetplatforms' is set to '${finalConfig.targetplatforms}', but there isn't any 'windows' agent requested."
    }
    cstConfigSuffix = '-windows'
  }
  String operatingSystem = finalConfig.targetplatforms.split('/')[0]

  if (operatingSystem == 'windows' && finalConfig.dockerBakeFile != '') {
    echo 'ERROR: docker bake is not (yet) supported on windows.'
    currentBuild.result = 'FAILURE'
    return
  }

  final InfraConfig infraConfig = new InfraConfig(env)
  final String defaultRegistryNamespace = infraConfig.getDockerRegistryNamespace()
  final String registryNamespace = finalConfig.registryNamespace ?: defaultRegistryNamespace
  final String imageName = registryNamespace + '/' + imageShortName

  echo "INFO: Resolved Container Image Name: ${imageName}"

  node(finalConfig.agentLabels) {
    withEnv([
      "BUILD_DATE=${buildDate}",
      "IMAGE_NAME=${imageName}",
      "IMAGE_DIR=${finalConfig.imageDir}",
      "IMAGE_DOCKERFILE=${finalConfig.dockerfile}",
      "BUILD_TARGETPLATFORM=${finalConfig.targetplatforms.split(',')[0]}",
      "BAKE_TARGETPLATFORMS=${finalConfig.targetplatforms}",
    ]) {
      infra.withDockerPullCredentials{
        String nextVersion = ''
        stage("Prepare ${imageName}") {
          checkout scm
          if (finalConfig.unstash != '') {
            unstash finalConfig.unstash
          }

          // The makefile to use must come from the pipeline to avoid a nasty user trying to exfiltrate data from the build
          // Even though we have mitigation through the multibranch job config allowing to build PRs only from the repository contributors
          writeFile file: 'Makefile', text: makefileContent
        } // stage

        stage("Lint ${imageName}") {
          // Define the image name as prefix to support multi images per pipeline
          String hadolintReportId = "${imageName.replaceAll(':','-').replaceAll('/','-')}-hadolint-${now.getTime()}"
          String hadoLintReportFile = "${hadolintReportId}.json"
          withEnv(["HADOLINT_REPORT=${env.WORKSPACE}/${hadoLintReportFile}"]) {
            try {
              if (isUnix()) {
                sh 'make lint'
              } else {
                powershell 'make lint'
              }
            } finally {
              boolean skipChecks = false
              if (env.BRANCH_IS_PRIMARY) {
                skipChecks = true
              }
              recordIssues(
                  skipPublishingChecks: skipChecks,
                  enabledForFailure: true,
                  aggregatingResults: false,
                  tool: hadoLint(id: hadolintReportId, pattern: hadoLintReportFile)
                  )
            }
          }
        } // stage

        stage("Build ${imageName}") {
          makecall('build', imageName, operatingSystem, finalConfig.dockerBakeFile, finalConfig.dockerBakeTarget)
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
                makecall('test', imageName, operatingSystem, finalConfig.dockerBakeFile, finalConfig.dockerBakeTarget)
              } // withEnv
            } //stage
          } else {
            echo "Skipping test ${testName} for ${imageName} as ${testHarness} does not exist"
          } // if else
        } // each

        if (env.BRANCH_IS_PRIMARY) {
          // Automatic tagging on principal branch is not enabled by default, show potential next version in PR anyway
          if (finalConfig.automaticSemanticVersioning) {
            stage("Get Next Version of ${imageName}") {
              if (isUnix()) {
                sh 'git fetch --all --tags' // Ensure that all the tags are retrieved (uncoupling from job configuration, wether tags are fetched or not)
                nextVersion = sh(script: finalConfig.nextVersionCommand, returnStdout: true).trim()
              } else {
                powershell 'git fetch --all --tags' // Ensure that all the tags are retrieved (uncoupling from job configuration, wether tags are fetched or not)
                nextVersion = powershell(script: finalConfig.nextVersionCommand, returnStdout: true).trim()
              }
              echo "Next Release Version = ${nextVersion}"
            } // stage
          } // if

          withEnv(["NEXT_VERSION=${nextVersion}"]) {
            // Only deploy on primary branch
            stage("Deploy ${imageName}") {
              if (!finalConfig.disablePublication) {
                infra.withDockerPushCredentials{
                  makecall('deploy', imageName, operatingSystem, finalConfig.dockerBakeFile, finalConfig.dockerBakeTarget)
                }
              } else {
                echo 'INFO: publication disabled.'
              } // else
            } // stage

            // Automatic tagging on principal branch is not enabled by default, and disabled if disablePublication is set to true
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
                  echo "Tagging and pushing the new version: ${nextVersion}"
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
                } // withCredentials
              } // stage
            } // if

            // GitHub Release stage: Use NEXT_VERSION and only on primary branch
            // Create release only if SemVer is enabled, on primary branch, and publication is NOT disabled.
            if (finalConfig.automaticSemanticVersioning && !finalConfig.disablePublication) {
              stage('GitHub Release') {
                withCredentials([
                  usernamePassword(credentialsId: "${finalConfig.gitCredentials}", passwordVariable: 'GITHUB_TOKEN', usernameVariable: 'GITHUB_USERNAME')
                ]) {
                  String release = ''
                  if (isUnix()) {
                    final String releaseScript = '''
                        originUrlWithGit="$(git remote get-url origin)"
                        originUrl="${originUrlWithGit%.git}"
                        org="$(echo "${originUrl}" | cut -d'/' -f4)"
                        repository="$(echo "${originUrl}" | cut -d'/' -f5)"
                        releasesUrl="/repos/${org}/${repository}/releases"
                        releaseId="$(gh api "${releasesUrl}" | jq -e -r '[ .[] | select(.draft == true and .name == "next").id] | max | select(. != null)')"
                        if test "${releaseId}" -gt 0
                        then
                          gh api -X PATCH -F draft=false -F name="${NEXT_VERSION}" -F tag_name="${NEXT_VERSION}" "${releasesUrl}/${releaseId}" > /dev/null
                        fi
                        echo "${releaseId}"
                      '''
                    release = sh(script: releaseScript, returnStdout: true)
                  } else {
                    final String releaseScript = '''
                        $originUrl = (git remote get-url origin) -replace '\\.git', ''
                        $org = $originUrl.split('/')[3]
                        $repository = $originUrl.split('/')[4]
                        $releasesUrl = "/repos/$org/$repository/releases"
                        $releaseId = (gh api $releasesUrl | jq -e -r '[ .[] | select(.draft == true and .name == "next").id] | max | select(. != null)')
                        $output = ''
                        if ($releaseId -gt 0)
                        {
                          Invoke-Expression -Command "gh api -X PATCH -F draft=false -F name=$env:NEXT_VERSION -F tag_name=$env:NEXT_VERSION $releasesUrl/$releaseId" > $null
                          $output = $releaseId
                        }
                        Write-Output $output
                      '''
                    release = powershell(script: releaseScript, returnStdout: true)
                  }
                  if (release == '') {
                    echo "No next release draft found."
                  } // if
                } // withCredentials
              } // stage
            } // if
          } // withEnv NEXT_VERSION
        } // if
      } // infra.withDockerPullCredentials
    } // withEnv (outer)
  } // node
} // call
