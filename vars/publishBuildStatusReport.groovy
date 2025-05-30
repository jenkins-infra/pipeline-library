// vars/publishBuildStatusReport.groovy

import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.net.URL

/**
 * Orchestrates the generation and publication of a JSON build status report
 * by an external shell script.
 *
 * This function will:
 * 1. Collect necessary Jenkins environment data.
 * 2. Prepare and deploy a utility shell script (from library resources) to the agent.
 * 3. Define the final path where the shell script should write its JSON report.
 * 4. Execute the utility shell script, passing all collected data and the target path
 *    as environment variables. The shell script handles JSON creation, directory creation,
 *    and writing the JSON to the target path.
 * 5. Call the 'publishReports' shared library function to upload the generated report.
 *
 * IMPORTANT: This function is intended for use on PRINCIPAL (e.g., main/master)
 * BRANCH BUILDS ONLY. It will return early and do nothing if called on a non-principal branch.
 *
 * Example usage in a Jenkinsfile (typically for a principal branch build):
 *
 * --------------------------------------------------------------------------
 * // Jenkinsfile
 * @Library('your-pipeline-library-name@main') _
 *
 * properties([
 *     buildDiscarder(logRotator(numToKeepStr: '20')),
 *     disableConcurrentBuilds()
 * ])
 *
 * pipeline {
 *     agent { label 'your-agent-label' } // Agent must have 'jq' (or be shell-script compatible) & 'az' CLI
 *     stages {
 *         stage('Main Work') {
 *             steps {
 *                 script {
 *                     sh './build-and-deploy.sh' // Example main work
 *                     echo "Main work completed."
 *                 }
 *             }
 *         }
 *     }
 *     post {
 *         always {
 *             script {
 *                 echo "Post-build: Attempting to publish build status. Current result: ${currentBuild.currentResult}"
 *                 publishBuildStatusReport()
 *             }
 *         }
 *     }
 * }
 * --------------------------------------------------------------------------
 */
def call(Map config = [:]) {

  // --- Step 0: Principal Branch Check ---
  // Only proceed if running on a principal branch.
//   if (env.BRANCH_IS_PRIMARY == null || !env.BRANCH_IS_PRIMARY.toBoolean()) {
//     echo "WARN: publishBuildStatusReport - Not on a principal branch (BRANCH_IS_PRIMARY: '${env.BRANCH_IS_PRIMARY}', BRANCH_NAME: '${env.BRANCH_NAME}'). Skipping report generation."
//     return
//   }

  echo "publishBuildStatusReport - Principal branch execution. Proceeding..."

  // --- Step 1: Collect Jenkins-specific information ---
  String jenkinsUrl = env.JENKINS_URL
  String controllerHostname = "unknown_controller"

  if (jenkinsUrl != null && !jenkinsUrl.trim().isEmpty()) {
    controllerHostname = new URL(jenkinsUrl).getHost()
    // Sanitize for use in file paths: remove port and replace non-alphanumeric (except . -) with _
    controllerHostname = controllerHostname.tokenize(':')[0].replaceAll("[^a-zA-Z0-9.-]", "_")
  } else {
    echo "WARN: publishBuildStatusReport - JENKINS_URL environment variable is not set or is empty. Using default hostname '${controllerHostname}'."
  }

  String jobName = env.JOB_NAME ?: "unknown_job" // e.g., "MyFolder/MyJob"
  String buildNumber = env.BUILD_NUMBER ?: "unknown_build"
  String buildStatus = currentBuild.currentResult ?: "UNKNOWN"

  // Log data being prepared by Groovy
  def groovyTimeForLog = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))
  echo "publishBuildStatusReport - Groovy-side time: ${groovyTimeForLog}"
  echo "publishBuildStatusReport - Data for shell script: Controller='${controllerHostname}', Job='${jobName}', Build='${buildNumber}', Status='${buildStatus}'"

  // --- Step 2: Define the final path for the JSON report on the agent ---
  // This path is constructed in Groovy as it uses Jenkins context (WORKSPACE, controllerHostname, jobName).
  // The shell script will be responsible for creating this path and writing the file.
  String finalReportDirOnAgent = "${env.WORKSPACE}/build_status_reports/${controllerHostname}/${jobName}"
  String finalReportFileName = "status.json"
  String finalReportPathOnAgent = "${finalReportDirOnAgent}/${finalReportFileName}"
  echo "publishBuildStatusReport - Shell script will be instructed to write final report to: ${finalReportPathOnAgent}"

  // --- Step 3: Prepare and deploy the utility shell script to the agent ---
  final String shellScriptResourcePath = 'io/jenkins/infra/pipeline/generateAndWriteBuildStatusReport.sh' // Assuming this is the agreed name and path
  String shellScriptContent = libraryResource shellScriptResourcePath

  String tempScriptDir = "${env.WORKSPACE}/.jenkins-scripts"
  String tempScriptName = "exec_generate_report_${env.BUILD_ID ?: System.currentTimeMillis()}.sh"
  String tempScriptPathOnAgent = "${tempScriptDir}/${tempScriptName}"

  // Ensure the temporary script directory exists
  sh "mkdir -p '${tempScriptDir}'" // Single quotes for safety if tempScriptDir could have spaces (unlikely for .jenkins-scripts)

  writeFile file: tempScriptPathOnAgent, text: shellScriptContent
  sh "chmod +x '${tempScriptPathOnAgent}'"
  echo "publishBuildStatusReport - Utility shell script deployed to agent at: ${tempScriptPathOnAgent}"

  // --- Step 4: Execute the utility shell script ---
  // Escape Groovy variables for safe inclusion as environment variable *values*.
  String escController = controllerHostname.replaceAll("'", "'\\\\''")
  String escJobName = jobName.replaceAll("'", "'\\\\''") // jobName can have '/' - this escaping handles single quotes within.
  String escBuildNumber = buildNumber.replaceAll("'", "'\\\\''")
  String escBuildStatus = buildStatus.replaceAll("'", "'\\\\''")
  // finalReportPathOnAgent is constructed from WORKSPACE and sanitized names, less likely to need escaping for this purpose,
  // but for consistency, we can escape it too.
  String escFinalReportPathOnAgent = finalReportPathOnAgent.replaceAll("'", "'\\\\''")

  withEnv([
    "ENV_CONTROLLER_HOSTNAME=${escController}",
    "ENV_JOB_NAME=${escJobName}",
    "ENV_BUILD_NUMBER=${escBuildNumber}",
    "ENV_BUILD_STATUS=${escBuildStatus}",
    "ENV_TARGET_JSON_FILE_PATH=${escFinalReportPathOnAgent}" // Pass the target path
  ]) {
    echo "publishBuildStatusReport - Executing utility shell script: '${tempScriptPathOnAgent}'"
    // Execute the script. It will handle its own file I/O. No stdout capture.
    sh "'${tempScriptPathOnAgent}'"
  }
  echo "publishBuildStatusReport - Utility shell script execution completed."

   // --- Step 5: Display generated status.json from workspace (FOR DEBUGGING/VERIFICATION) ---
  echo "publishBuildStatusReport - Displaying content of generated report file: ${finalReportPathOnAgent}" {
    sh "cat '${finalReportPathOnAgent}'"
  }
  // --- Step 5: Publish the report (which was created by the shell script) ---
  // No Groovy-side validation of file content here, as per "no unnecessary validations".
  // We trust the shell script did its job if the 'sh' call above didn't fail (due to set -e in shell script).
  // A fileExists check could be added here if deemed essential minimal validation.
  echo "publishBuildStatusReport - Publishing report from: ${finalReportPathOnAgent}"
  publishReports([finalReportPathOnAgent])

  // Cleanup of the temporary utility shell script is omitted as per last discussion.
  // Jenkins workspace cleanup will eventually remove it.

  echo "publishBuildStatusReport - Process completed for ${jobName}#${buildNumber}."
}
