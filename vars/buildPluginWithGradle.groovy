#!/usr/bin/env groovy

/**
 * Simple wrapper step for building a plugin with Gradle.
 */
def call(Map params = [:]) {
  properties([buildDiscarder(logRotator(numToKeepStr: '5'))])

  def repo = params.containsKey('repo') ? params.repo : null
  def failFast = params.containsKey('failFast') ? params.failFast : true
  def timeoutValue = params.containsKey('timeout') ? params.timeout : 60
  if (timeoutValue > 180) {
    echo "Timeout value requested was $timeoutValue, lowering to 180 to avoid Jenkins project's resource abusive consumption"
    timeoutValue = 180
  }

  boolean publishingIncrementals = false
  boolean archivedArtifacts = false
  Map tasks = [failFast: failFast]
  buildPlugin.getConfigurations(params).each { config ->
    String label = config.platform
    String jdk = config.jdk
    String jenkinsVersion = config.jenkins

    String stageIdentifier = "${label}-${jdk}${jenkinsVersion ? '-' + jenkinsVersion : ''}"
    boolean skipTests = params?.tests?.skip

    tasks[stageIdentifier] = {
      node(label) {
        timeout(timeoutValue) {
          // Archive artifacts once with pom declared baseline
          boolean doArchiveArtifacts = !jenkinsVersion && !archivedArtifacts
          if (doArchiveArtifacts) {
            archivedArtifacts = true
          }

          stage("Checkout (${stageIdentifier})") {
            infra.checkoutSCM(repo)
          }

          stage("Build (${stageIdentifier})") {
            if (config.containsKey('javaLevel')) {
              infra.publishDeprecationCheck('Remove javaLevel', 'Ignoring deprecated "javaLevel" parameter. This parameter should be removed from your "Jenkinsfile".')
            }
            //TODO(oleg-nenashev): Once supported by Gradle JPI Plugin, pass jenkinsVersion
            if (jenkinsVersion) {
              echo "WARNING: 'jenkinsVersion' parameter is not supported in buildPluginWithGradle(). It will be ignored"
            }
            List<String> gradleOptions = ['--no-daemon', 'cleanTest', 'build']
            if (skipTests) {
              gradleOptions += '--exclude-task test'
            }
            String command = "gradlew ${gradleOptions.join(' ')}"
            if (isUnix()) {
              command = "./" + command
            }
            try {
              infra.runWithJava(command, jdk)
            } finally {
              if (!skipTests) {
                junit('**/build/test-results/**/*.xml')
              }
            }
          }

          stage("Archive (${stageIdentifier})") {
            //TODO(oleg-nenashev): Add static analysis results publishing like in buildPlugin() for Maven

            if (failFast && currentBuild.result == 'UNSTABLE') {
              error 'There were test failures; halting early'
            }
            if (doArchiveArtifacts) {
              archiveArtifacts artifacts: '**/build/libs/*.hpi,**/build/libs/*.jpi', fingerprint: true, allowEmptyArchive: true
            }
          }
        }
      }
    }
  }

  parallel(tasks)
}
