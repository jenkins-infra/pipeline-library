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

  final String operatingSystem = finalConfig.platform.split('/')[0]
  final String cpuArch = finalConfig.platform.split('/')[1]
  if (finalConfig.agentLabels.contains('windows')) {
    operatingSystem = 'Windows'
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

          if (operatingSystem == 'Windows') {
            // Logging in on the Dockerhub helps to avoid request limit from DockerHub
            powershell 'echo "$env:DOCKER_REGISTRY_PSW" | docker login -u "$env:DOCKER_REGISTRY_USR" -p "$env:DOCKER_REGISTRY_PSW"'// --password-stdin'

            // Custom tools might be installed in the .bin directory in the workspace
            powershell '''
              echo "------------ INIT ----------------"
              Remove-Item "$env:WORKSPACE/.bin" -Recurse
              mkdir -p "$env:WORKSPACE/.bin"
              # Add folder to $PATH
              $env:Path += "$env:WORKSPACE\\.bin"
              # Add "tools" folder to $PATH
              # $env:Path += ";C:\\tools\\"

              # debug tools folder
              echo "----------- TOOLS ----------------"
              # Get-ChildItem -Recurse "C:\\tools"

              # debug
              # dir env:
            '''

          } else {
            // Logging in on the Dockerhub helps to avoid request limit from DockerHub
            sh 'echo "${DOCKER_REGISTRY_PSW}" | "${CONTAINER_BIN}" login -u "${DOCKER_REGISTRY_USR}" --password-stdin'

            // Custom tools might be installed in the .bin directory in the workspace
            sh 'mkdir -p "${WORKSPACE}/.bin"'
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
            if (finalConfig.nextVersionCommand.contains('jx-release-version')) {
              withEnv([
                "jxrv_url=https://github.com/jenkins-x-plugins/jx-release-version/releases/download/v2.5.1/jx-release-version-${operatingSystem}-${cpuArch}.tar.gz", // TODO: track with updatecli
              ]) {
                echo "TODO: download jx-release-version"
                // powershell '''
                // if ! command -v jx-release-version 2>/dev/null >/dev/null
                // then
                //   echo "INFO: No jx-release-version binary found: Installing it from ${jxrv_url}."
                //   curl --silent --location "${jxrv_url}" | tar xzv
                //   mv ./jx-release-version "${WORKSPACE}/.bin/jx-release-version"
                // fi
                // '''
              }
            }
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
          "PATH+TOOLS=C:/tools",
          "HADOLINT_REPORT=${env.WORKSPACE}/${hadoLintReportFile}",
          "hadolint_url=https://github.com/hadolint/hadolint/releases/download/v2.10.0/hadolint-${operatingSystem}-${cpuArch}.exe", // TODO: track with updatecli
        ]) {
          try {
            if (operatingSystem == 'Windows') {
              powershell 'echo $env:Path'
              powershell '''
              # Check if hadolint is installed
              if (-Not (Get-Command 'hadolint.exe' -errorAction SilentlyContinue))
              {
                echo "INFO: No hadolint binary found: Installing it from $env:hadolint_url"
                # Invoke-WebRequest "$env:hadolint_url" -OutFile "$env:WORKSPACE\\.bin\\hadolint.exe"
              } else {
                echo "INFO: hadolint binary found"
              }

              # Convert EOL
              $dockerfileOrig = ($env:WORKSPACE + "\\" + $env:IMAGE_DOCKERFILE.replace('/', '\\'))
              $dockerfile = $dockerfileOrig + '.win'
              Get-Content -Path $dockerfileOrig | Out-File -FilePath $dockerfile -Encoding utf8

              # Run hadolint
              $hadolintReport = $env:HADOLINT_REPORT.replace('/', '\\')
              $folder = (Split-Path -Path $hadolintReport)
              "C:\\tools\\hadolint.exe --format=json $dockerfile" | Out-File -FilePath $hadolintReport
              dir $folder
              type $hadolintReport
              '''
            } else {
              sh 'make lint'
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
        if (operatingSystem == 'Windows') {
          powershell '''
          	$dockerfile = ($env:WORKSPACE + "\\" + $env:IMAGE_DOCKERFILE.replace('/', '\\') + '.win')
            $folder = (Split-Path -Path $dockerfile)
            $archive = "$folder\\image.tar"
            echo "dockerfile: $dockerfile"
            echo "folder: $folder"
            echo "archive: $archive"
            echo "== Building $env:IMAGE_NAME from $env:IMAGE_DOCKERFILE..."

            # TODO: missing env vars

            docker build \
              --tag $env:IMAGE_NAME \
              --build-arg "GIT_COMMIT_REV=$env:GIT_COMMIT_REV" \
              --build-arg "GIT_SCM_URL=$env:GIT_SCM_URL" \
              --build-arg "BUILD_DATE=$env:BUILD_DATE" \
              --label "org.opencontainers.image.source=$env:GIT_SCM_URL" \
              --label "org.label-schema.vcs-url=$env:GIT_SCM_URL" \
              --label "org.opencontainers.image.url=$env:SCM_URI" \
              --label "org.label-schema.url=$env:SCM_URI" \
              --label "org.opencontainers.image.revision=$env:GIT_COMMIT_REV" \
              --label "org.label-schema.vcs-ref=$env:GIT_COMMIT_REV" \
              --label "org.opencontainers.image.created=$env:BUILD_DATE" \
              --label "org.label-schema.build-date=$env:BUILD_DATE" \
              --file $dockerfile \
              $folder
            docker save --output=$archive $env:IMAGE_NAME
            echo "== Build Succeeded, image $env:IMAGE_NAME exported to $archive"
            dir
            dir $folder
          '''
        } else {
          sh 'make build'
        } // if
      } //stage

      // There can be 2 kind of tests: per image and per repository
      [
        'Image Test Harness': "${finalConfig.imageDir}/cst.yml",
        'Common Test Harness': "${env.WORKSPACE}/common-cst.yml"
      ].each { testName, testHarness ->
        if (fileExists(testHarness)) {
          stage("Test ${testName} for ${imageName}") {
            withEnv([
              "TEST_HARNESS=${testHarness}",
              "cst_url=https://github.com/GoogleContainerTools/container-structure-test/releases/download/v1.11.0/container-structure-test-${operatingSystem}-${cpuArch}", // TODO: track with updatecli
            ]) {
              if (operatingSystem == 'Windows') {
                echo "TODO: Test Harness $env:TEST_HARNESS not yet supported on Windows"
                // $(CST_BIN) test --driver=$(CST_DRIVER) --image=$(CST_SUT) --config=$(TEST_HARNESS)
              } else {
                sh 'make test'
              } // if
            } // withEnv
          } //stage
        } // if
      } // each

      // Automatic tagging on principal branch is not enabled by default
      if (semVerEnabled) {
        stage("Semantic Release of ${imageName}") {
          echo "Configuring credential.helper"
          if (operatingSystem == 'Windows') {
            echo "TODO: credential.helper not yet supported on Windows"
            //powershell 'git config --local credential.helper "!f() { echo username=\\$GIT_USERNAME; echo password=\\$GIT_PASSWORD; }; f"'
          } else {
            sh 'git config --local credential.helper "!f() { echo username=\\$GIT_USERNAME; echo password=\\$GIT_PASSWORD; }; f"'
          }

          withCredentials([
            usernamePassword(credentialsId: "${finalConfig.gitCredentials}", passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')
          ]) {
            withEnv(["NEXT_VERSION=${nextVersion}"]) {
              if (operatingSystem == 'Windows') {
                echo 'TODO: Semantic Release not yet supported on Windows'
              } else {
                echo "Tagging and pushing the new version: $nextVersion"
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
            if (operatingSystem == 'Windows') {
              powershell '''
              echo "== Deploying $env:IMAGE_NAME to $env:IMAGE_DEPLOY_NAME"
              docker tag $env:IMAGE_NAME $env:IMAGE_DEPLOY_NAME
              docker push $env:IMAGE_DEPLOY_NAME
              echo "== Deploy Succeeded"
              '''
            } else {
              // Please note that "make deploy" uses the environment variable "IMAGE_DEPLOY_NAME"
              sh 'make deploy'
            } // if
          } // withEnv
        } //stage
      // } // if

      if (env.TAG_NAME && finalConfig.automaticSemanticVersioning) {
        stage('GitHub Release') {
          withCredentials([
            usernamePassword(credentialsId: "${finalConfig.gitCredentials}", passwordVariable: 'GITHUB_TOKEN', usernameVariable: 'GITHUB_USERNAME')
          ]) {
            final String origin = sh(returnStdout: true, script: 'git remote -v | grep origin | grep push | sed \'s/^origin\\s//\' | sed \'s/\\s(push)//\'').trim() - '.git'
            final String org = origin.split('/')[3]
            final String repository = origin.split('/')[4]
            final String ghVersion = '2.5.2' // TODO: track with updatecli
            final String platformId = "${operatingSystem}_${cpuArch}"
            final String ghUrl = "https://github.com/cli/cli/releases/download/v${ghVersion}/gh_${ghVersion}_${platformId}.tar.gz"
            final String ghReleasesApiUri = "/repos/${org}/${repository}/releases"
            withEnv([
              "GH_URL=${ghUrl}",
              "GH_RELEASES_API_URI=${ghReleasesApiUri}",
            ]) {
              if (operatingSystem == 'Windows') {
                echo 'TODO: GitHub Release not yet supported on Windows'
              } else {
                final String release = sh(returnStdout: true, script: 'gh api ${GH_RELEASES_API_URI} | jq -e -r \'.[] | select(.draft == true and .name == "next") | .id\'').trim()
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
        'HADOLINT_BIN=docker run --rm hadolint/hadolint:latest hadolint', // Do not put the command (right part of the assignation) between quotes to ensure that bash treat it as an array of strings
      ]) {
        body.call()
      }
    }
  }
}
