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
def call(Map params = [:]) {
  if (!env.BRANCH_IS_PRIMARY) {
    return
  }

  if (!env.JENKINS_URL?.trim()) {
    error('JENKINS_URL is not set or empty')
  }

  // Write the script to temp directory
  def tempDir = pwd(tmp: true)
  def scriptPath = "${tempDir}/generateAndWriteBuildStatusReport.sh"
  writeFile file: scriptPath, text: libraryResource('io/jenkins/infra/pipeline/generateAndWriteBuildStatusReport.sh')

  def jobName = params.containsKey('jobName') ? params.jobName : (env.JOB_NAME ?: '')
  def buildNumber = params.containsKey('buildNumber') ? params.buildNumber : (env.BUILD_NUMBER ?: '')
  def buildStatus = params.containsKey('buildStatus') ? params.buildStatus : (currentBuild.currentResult ?: 'UNKNOWN')

  if (!jobName?.trim()) {
    error('Neither JOB_NAME nor params.jobName is set or not empty')
  }
  if (!buildNumber?.trim()) {
    error('Neither BUILD_NUMBER nor params.buildNumber is set or not empty')
  }
  if (!buildStatus?.trim()) {
    error('REPORT_BUILD_STATUS nor params.buildStatus is set or not empty')
  }

  withEnv([
    "REPORT_JOB_NAME=${jobName}",
    "REPORT_BUILD_NUMBER=${buildNumber}",
    "REPORT_BUILD_STATUS=${buildStatus}",
    "SCRIPT_PATH=${scriptPath}"
  ]) {
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
