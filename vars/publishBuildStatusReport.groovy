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
  if (env.BRANCH_IS_PRIMARY == null || !env.BRANCH_IS_PRIMARY.toBoolean()) {
    return
  }

  if (!env.JENKINS_URL?.trim()) {
    error("JENKINS_URL is not set or empty")
  }

  def tempDir = pwd(tmp: true)

  withEnv(["BUILD_STATUS=${currentBuild.currentResult ?: 'UNKNOWN'}"]) {
    sh '''
            cat > ${tempDir}/generateAndWriteBuildStatusReport.sh << 'SCRIPT_EOF'
            ${libraryResource('io/jenkins/infra/pipeline/generateAndWriteBuildStatusReport.sh')}
            SCRIPT_EOF
            bash ${tempDir}/generateAndWriteBuildStatusReport.sh
        '''
  }
}
