/**
 Note: this shared pipeline is tailored to Jenkins infrastructure usage and thus sets some default values which might not be desirable for yours.
 **/

def call(userConfig = [:]) {
  def defaultConfig = [
    action: 'diff',                         // Updatecli subcommand to execute
    config: './updatecli/updatecli.d',        // Config manifest (file or directory) for updatecli
    values: './updatecli/values.yaml',        // Values file used by updatecli
    updatecliAgentLabel: 'jnlp-linux-arm64',  // Label to select the Jenkins node (agent)
    cronTriggerExpression: '',                // When specified, it enables cron trigger for the calling pipeline
    credentialsId: 'github-app-updatecli-on-jenkins-infra', // Credentials for GitHub access
    version: ''                               // New: custom updatecli version (e.g. '0.92.0' or '0.86.0-rc.1')
  ]

  // Merge userConfig into defaultConfig (userConfig overrides defaults)
  final Map finalConfig = defaultConfig << userConfig

  // Build the updatecli command if no custom version is provided
  def updatecliCommand = ""
  if (!finalConfig.version) {
    updatecliCommand = "updatecli ${finalConfig.action}"
    updatecliCommand += finalConfig.config ? " --config ${finalConfig.config}" : ""
    updatecliCommand += finalConfig.values ? " --values ${finalConfig.values}" : ""
  }

  // Set cron trigger if requested
  if (finalConfig.cronTriggerExpression) {
    properties([pipelineTriggers([cron(finalConfig.cronTriggerExpression)])])
  }

  node (finalConfig.updatecliAgentLabel) {
    final String updatecliRunStage = "Run updatecli: ${finalConfig.action}"
    boolean runUpdatecli = true

    stage("Check if ${finalConfig.config} folder exists: ${finalConfig.action}") {
      checkout scm
      if (!fileExists(finalConfig.config)) {
        echo "WARNING: no ${finalConfig.config} folder."
        runUpdatecli = false
        org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional(updatecliRunStage)
      }
    }

    // If a custom version is provided, download and install that version of updatecli using tar files in a single sh step
    if (runUpdatecli && finalConfig.version) {
      stage("Download updatecli version ${finalConfig.version}") {
        withEnv(["UPDATECLI_VERSION=${finalConfig.version}"]) {
          sh '''
            versionTag="v${UPDATECLI_VERSION}"
            cpu="$(uname -m)"
            # Construct the tar file name based on the CPU architecture.
            tarFileName="updatecli_Linux_${cpu}.tar.gz" 
            downloadUrl="https://github.com/updatecli/updatecli/releases/download/${versionTag}/${tarFileName}"
            echo "Downloading updatecli version ${UPDATECLI_VERSION} from ${downloadUrl}"
            curl --silent --location --output ${tarFileName} ${downloadUrl}
            if [ $? -ne 0 ]; then
              echo "Updatecli custom download failed"
              exit 1
            fi
            # Create destination directory for extraction.
            mkdir -p /tmp/custom_updatecli
            # Extract the updatecli binary from the tar file.
            tar --extract --gzip --file="\${tarFileName}" --directory="/tmp/custom_updatecli" updatecli
            # Remove the tar file leaving only the executable binary.
            rm -f ${tarFileName}
            # To use the downloaded updatecli version, update the PATH.
            echo "Using updatecli version: $(updatecli version)"
          '''
        }
        // Build the command to use the locally extracted binary using its absolute path.
        def destPath = "/tmp/custom_updatecli"
        updatecliCommand = "${destPath}/updatecli ${finalConfig.action}"
        updatecliCommand += finalConfig.config ? " --config ${finalConfig.config}" : ""
        updatecliCommand += finalConfig.values ? " --values ${finalConfig.values}" : ""
        // Optionally, verify the downloaded binary's version
        sh "${destPath}/updatecli version"
      }
    }

    stage(updatecliRunStage) {
      if (runUpdatecli) {
        withCredentials([
          usernamePassword(
            credentialsId: finalConfig.credentialsId,
            usernameVariable: 'USERNAME_VALUE',
            passwordVariable: 'UPDATECLI_GITHUB_TOKEN'
          )
        ]) {
          // For the default case (no custom version), check the existing updatecli version.
          if (!finalConfig.version) {
            sh 'updatecli version'
          }
          sh updatecliCommand
        }
      }
    }
  }
}
