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
              infra.publishDeprecationCheck('Remove jenkinsVersion', 'The "jenkinsVersion" parameter is not supported in buildPluginWithGradle(). It will be ignored.')
            }
            infra.publishDeprecationCheck('Migrate from Gradle to Maven', 'The Jenkins project offers only partial support for building plugins with Gradle and "gradle-jpi-plugin". The Jenkins project offers full support only for building plugins with Maven and "maven-hpi-plugin".')
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
            if (failFast && currentBuild.result == 'UNSTABLE') {
              error 'There were test failures; halting early'
            }
            echo "Recording static analysis results on '${stageIdentifier}'"

            recordIssues(
                enabledForFailure: true,
                tools: [java(), javaDoc()],
                filters: [excludeFile('.*Assert.java')],
                sourceCodeEncoding: 'UTF-8',
                skipBlames: true,
                trendChartType: 'TOOLS_ONLY'
                )

            // Default configuration for SpotBugs can be overwritten using a `spotbugs`, `checkstyle', etc. parameter (map).
            // Configuration see: https://github.com/jenkinsci/warnings-ng-plugin/blob/master/doc/Documentation.md#configuration
            Map spotbugsArguments = [tool: spotBugs(pattern: '**/build/reports/spotbugs/*.xml'),
              sourceCodeEncoding: 'UTF-8',
              skipBlames: true,
              trendChartType: 'TOOLS_ONLY',
              qualityGates: [[threshold: 1, type: 'NEW', unstable: true]]]
            if (params?.spotbugs) {
              spotbugsArguments.putAll(params.spotbugs as Map)
            }
            recordIssues spotbugsArguments

            Map checkstyleArguments = [tool: checkStyle(pattern: '**/build/reports/checkstyle/*.xml'),
              sourceCodeEncoding: 'UTF-8',
              skipBlames: true,
              trendChartType: 'TOOLS_ONLY',
              qualityGates: [[threshold: 1, type: 'TOTAL', unstable: true]]]
            if (params?.checkstyle) {
              checkstyleArguments.putAll(params.checkstyle as Map)
            }
            recordIssues checkstyleArguments

            Map jacocoArguments = [tools: [[parser: 'JACOCO', pattern: '**/build/reports/jacoco/**/*.xml']], sourceCodeRetention: 'MODIFIED']
            if (params?.jacoco) {
              jacocoArguments.putAll(params.jacoco as Map)
            }
            recordCoverage jacocoArguments


            recordIssues(
                enabledForFailure: true, tool: taskScanner(
                includePattern:'**/*.java',
                excludePattern:'**/build/**',
                highTags:'FIXME',
                normalTags:'TODO'),
                sourceCodeEncoding: 'UTF-8',
                skipBlames: true,
                trendChartType: 'NONE'
                )
            if (failFast && currentBuild.result == 'UNSTABLE') {
              error 'Static analysis quality gates not passed; halting early'
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
