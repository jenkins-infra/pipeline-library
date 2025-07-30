/**
 * Generates and publishes a JSON build status report for monitoring Jenkins builds.
 *
 * This function deploys and executes a shell script that handles all processing:
 * hostname extraction, path construction, JSON generation, and upload to Azure storage.
 *
 * IMPORTANT: Only runs on principal branches (BRANCH_IS_PRIMARY=true).
 *
 * Example usage:
 *
 * @Library('pipeline-library@main') _
 * buildDockerAndPublishImage('404', [
 *     targetplatforms: 'linux/amd64,linux/arm64',
 * ])
 * node('linux-arm64') {
 *     stage('[Post-Build] Status Report') {
 *         publishBuildStatusReport()
 *     }
 * }
 */
def call(Map config = [:]) {
  if (!env.BRANCH_IS_PRIMARY) {
    return
  }

  if (!env.JENKINS_URL?.trim()) {
    error("JENKINS_URL is not set or empty")
  }

  def tempDir = pwd(tmp: true)
  def scriptPath = "${tempDir}/generateAndWriteBuildStatusReport.sh"

  // Write the script to temp directory
  writeFile file: scriptPath, text: libraryResource('io/jenkins/infra/pipeline/generateAndWriteBuildStatusReport.sh')

  // Make script executable and run it
  withEnv(["BUILD_STATUS=${currentBuild.currentResult ?: 'UNKNOWN'}", "SCRIPT_PATH=${scriptPath}"]) {
    try {
      sh '''
            bash ${SCRIPT_PATH}
        '''
    } catch (err) {
      currentBuild.result = 'FAILURE'
      sh '''
            # Retrieve azcopy logs to archive them
            cat $HOME/.azcopy/*.log > azcopy.log 2>/dev/null || echo "No azcopy logs found"
        '''
      archiveArtifacts 'azcopy.log'
      throw err
    }
  }
}
