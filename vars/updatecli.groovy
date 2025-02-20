/**
 Note: this shared pipeline is tailored to Jenkins infrastructure usage and thus sets some default values which might not be desirable for yours.
 **/

def call(userConfig = [:]) {
  def defaultConfig = [
    action: 'diff', // Updatecli subcommand to execute
    config: './updatecli/updatecli.d', // Config manifest (file or directory) for updatecli
    values: './updatecli/values.yaml', // Values file used by updatecli
    updatecliAgentLabel: 'jnlp-linux-arm64', // Label to select the Jenkins node (agent)
    cronTriggerExpression: '', // When specified, it enables cron trigger for the calling pipeline
    credentialsId: 'github-app-updatecli-on-jenkins-infra', // Credentials for GitHub access (used for token generation)
    version: '' // New: custom updatecli version (e.g. '0.92.0' or '0.86.0-rc.1')
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

    // If a custom version is provided, download that version of updatecli
    if (runUpdatecli && finalConfig.version) {
      stage("Download updatecli version ${finalConfig.version}") {
        // Detect operating system (we expect Linux or Windows)
        def os = isUnix() ? "linux" : "windows"
        // Determine CPU architecture
        def cpu = ""
        if (isUnix()) {
          cpu = sh(script: "uname -m", returnStdout: true).trim()
        } else {
          cpu = bat(script: "echo %PROCESSOR_ARCHITECTURE%", returnStdout: true).trim()
        }
        // Normalize CPU names to expected values
        if (cpu == "x86_64" || cpu == "AMD64") {
          cpu = "amd64"
        } else if (cpu == "aarch64" || cpu == "arm64") {
          cpu = "arm64"
        }
        // Construct the file name (append .exe for Windows)
        def fileName = "updatecli-${os}-${cpu}"
        if (!isUnix()) {
          fileName += ".exe"
        }
        // Build the download URL (note the "v" prefix in the version tag)
        def versionTag = "v${finalConfig.version}"
        def downloadUrl = "https://github.com/updatecli/updatecli/releases/download/${versionTag}/${fileName}"
        echo "Downloading updatecli version ${finalConfig.version} from ${downloadUrl}"
        // Download the binary using curl (Unix) or PowerShell (Windows)
        def downloadResult = 0
        if (isUnix()) {
          downloadResult = sh(script: "curl -fL ${downloadUrl} -o updatecli", returnStatus: true)
        } else {
          downloadResult = bat(script: "powershell -Command \"Invoke-WebRequest -Uri '${downloadUrl}' -OutFile 'updatecli.exe'\"", returnStatus: true)
        }
        if (downloadResult != 0) {
          error "Specified updatecli version ${finalConfig.version} not found at ${downloadUrl}"
        }
        // For Unix, make the binary executable and build the command using the downloaded binary
        if (isUnix()) {
          sh "chmod +x updatecli"
          updatecliCommand = "./updatecli ${finalConfig.action}"
        } else {
          updatecliCommand = "updatecli.exe ${finalConfig.action}"
        }
        updatecliCommand += finalConfig.config ? " --config ${finalConfig.config}" : ""
        updatecliCommand += finalConfig.values ? " --values ${finalConfig.values}" : ""
        // Optionally, verify the downloaded binary's version
        if (isUnix()) {
          sh "./updatecli version"
        } else {
          sh "updatecli.exe version"
        }
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
          // For the default case (no custom version), check the existing updatecli version
          if (!finalConfig.version) {
            sh 'updatecli version'
          }
          sh updatecliCommand
        }
      }
    }
  }
}
