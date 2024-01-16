import io.jenkins.infra.InfraConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.text.DateFormat

def call(String imageShortName, Map userConfig=[:]) {
  def defaultConfig = [
    agentLabels: 'docker || linux-amd64-docker', // String expression for the labels the agent must match
    automaticSemanticVersioning: false, // Do not automagically increase semantic version by default
    includeImageNameInTag: false, // Set to true for multiple semversioned images built in parallel, will include the image name in tag to avoid conflict
    dockerfile: 'Dockerfile', // Obvious default
    platform: 'linux/amd64', // Intel/AMD 64 Bits, following Docker platform identifiers
    nextVersionCommand: 'jx-release-version', // Commmand line used to retrieve the next version
    gitCredentials: 'github-app-infra', // Credential ID for tagging and creating release
    imageDir: '.', // Relative path to the context directory for the Docker build
    registryNamespace: '', // Empty by default (means "autodiscover based on the current controller")
    unstash: '', // Allow to unstash files if not empty
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
    cstConfigSuffix = '-windows'
  }
  String operatingSystem = finalConfig.platform.split('/')[0]

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
      "IMAGE_PLATFORM=${finalConfig.platform}",
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

        // Automatic tagging on principal branch is not enabled by default, show potential next version in PR anyway
        if (finalConfig.automaticSemanticVersioning) {
          stage("Get Next Version of ${imageName}") {
            String imageInTag = '-' + imageName.replace('-','').replace(':','').toLowerCase()
            if (isUnix()) {
              sh 'git fetch --all --tags' // Ensure that all the tags are retrieved (uncoupling from job configuration, wether tags are fetched or not)
              if (!finalConfig.includeImageNameInTag) {
                nextVersion = sh(script: finalConfig.nextVersionCommand, returnStdout: true).trim()
              } else {
                echo "Including the image name '${imageName}' in the next version"
                // Retrieving the semver part from the last tag including the image name
                String currentTagScript = 'git tag --list \"*' + imageInTag + '\" --sort=-v:refname | head -1'
                String currentSemVerVersion = sh(script: currentTagScript, returnStdout: true).trim()
                echo "Current semver version is '${currentSemVerVersion}'"
                // Set a default value if there isn't any tag for the current image yet (https://groovy-lang.org/operators.html#_elvis_operator)
                currentSemVerVersion = currentSemVerVersion ?: '0.0.0-' + imageInTag
                String nextVersionScript = finalConfig.nextVersionCommand + ' -debug --previous-version=' + currentSemVerVersion
                String nextVersionSemVerPart = sh(script: nextVersionScript, returnStdout: true).trim()
                echo "Next semver version part is '${nextVersionSemVerPart}'"
                nextVersion =  nextVersionSemVerPart + imageInTag
              }
            } else {
              powershell 'git fetch --all --tags' // Ensure that all the tags are retrieved (uncoupling from job configuration, wether tags are fetched or not)
              if (!finalConfig.includeImageNameInTag) {
                nextVersion = powershell(script: finalConfig.nextVersionCommand, returnStdout: true).trim()
              } else {
                echo "Including the image name '${imageName}' in the next version"
                // Retrieving the semver part from the last tag including the image name
                String currentTagScript = 'git tag --list \"*' + imageInTag + '\" --sort=-v:refname | head -1'
                String currentSemVerVersion = powershell(script: currentTagScript, returnStdout: true).trim()
                echo "Current semver version is '${currentSemVerVersion}'"
                // Set a default value if there isn't any tag for the current image yet (https://groovy-lang.org/operators.html#_elvis_operator)
                currentSemVerVersion = currentSemVerVersion ?: '0.0.0-' + imageInTag
                String nextVersionScript = finalConfig.nextVersionCommand + ' -debug --previous-version=' + currentSemVerVersion
                String nextVersionSemVerPart = powershell(script: nextVersionScript, returnStdout: true).trim()
                echo "Next semver version part is '${nextVersionSemVerPart}'"
                nextVersion =  nextVersionSemVerPart + imageInTag
              }
            }
            echo "Next Release Version = ${nextVersion}"
          } // stage
        } // if

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
              } // withEnv
            } // withCredentials
          } // stage
        } // if
      }// withDockerPullCredentials
      infra.withDockerPushCredentials{
        if (env.TAG_NAME || env.BRANCH_IS_PRIMARY) {
          stage("Deploy ${imageName}") {
            String imageDeployName = imageName
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
                  gh api -X PATCH -F draft=false -F name="${TAG_NAME}" -F tag_name="${TAG_NAME}" "${releasesUrl}/${releaseId}" > /dev/null
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
                $releaseId = (gh api $releasesUrl | jq -e -r '[ .[] | select(.draft == true and .name == \"next\").id] | max | select(. != null)')
                $output = ''
                if ($releaseId -gt 0)
                {
                  Invoke-Expression -Command "gh api -X PATCH -F draft=false -F name=$env:TAG_NAME -F tag_name=$env:TAG_NAME $releasesUrl/$releaseId" > $null
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
    } // withEnv
  } // node
} // call
