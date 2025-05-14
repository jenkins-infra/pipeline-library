#!/usr/bin/env groovy

/**
 * Simple wrapper step for building a plugin with Gradle.
 */
def call(Map params = [:]) {
  properties([buildDiscarder(logRotator(numToKeepStr: '5'))])

  def repo = params.containsKey('repo') ? params.repo : null
  def useContainerAgent = params.containsKey('useContainerAgent') ? params.useContainerAgent : false
  def failFast = params.containsKey('failFast') ? params.failFast : true
  def timeoutValue = params.containsKey('timeout') ? params.timeout : 60
  if (timeoutValue > 180) {
    echo "Timeout value requested was $timeoutValue, lowering to 180 to avoid Jenkins project's resource abusive consumption"
    timeoutValue = 180
  }

  def incrementals = !params.containsKey('noIncrementals')
  boolean publishingIncrementals = false
  boolean archivedArtifacts = false
  Map tasks = [failFast: failFast]
  buildPlugin.getConfigurations(params).each { config ->
    String label = infra.getBuildAgentLabel(config.platform, config.jdk, useContainerAgent)
    String jdk = config.jdk
    String jenkinsVersion = config.jenkins

    String stageIdentifier = "${label}-${jdk}${jenkinsVersion ? '-' + jenkinsVersion : ''}"
    boolean first = tasks.size() == 1
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

          String m2repo
          List<String> changelist

          stage("Build (${stageIdentifier})") {
            m2repo = "${pwd tmp: true}/m2repo"
            //TODO(oleg-nenashev): Once supported by Gradle JPI Plugin, pass jenkinsVersion
            if (jenkinsVersion) {
              infra.publishDeprecationCheck('Remove jenkinsVersion', 'The "jenkinsVersion" parameter is not supported in buildPluginWithGradle(). It will be ignored.')
            }
            List<String> gradleOptions = ['--no-daemon', 'cleanTest', 'build']
            if (skipTests) {
              gradleOptions += '--exclude-task test'
            }

            if (incrementals) {
              changelist = tryGenerateVersion(jdk)
            }
            try {
              if (changelist) {
                // assumes the project does not set its own version in build.gradle with `version=foo`, it can be set
                // in gradle.properties though.
                infra.runWithJava(infra.gradleCommand([
                  *gradleOptions,
                  'publishToMavenLocal',
                  "-Dmaven.repo.local=$m2repo",
                  "-Pversion=${changelist[0]}",
                  "-PscmTag=${changelist[1]}"
                ]), jdk)
              } else {
                infra.runWithJava(infra.gradleCommand(gradleOptions), jdk)
              }
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
            if (first) {
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
                if (changelist) {
                  dir(m2repo) {
                    fingerprint '**/*-rc*.*/*-rc*.*' // includes any incrementals consumed
                    archiveArtifacts artifacts: "**/*${changelist[0]}/*${changelist[0]}*",
                    excludes: '**/*.lastUpdated',
                    allowEmptyArchive: true // in case we forgot to reincrementalify
                  }
                  publishingIncrementals = true
                } else {
                  archiveArtifacts artifacts: '**/build/libs/*.hpi,**/build/libs/*.jpi', fingerprint: true, allowEmptyArchive: true
                }
              }
            } else {
              echo "Skipping static analysis results for ${stageIdentifier}"
            }
          }
        }
      }
    }
  }

  parallel(tasks)
  if (publishingIncrementals) {
    infra.maybePublishIncrementals()
  }
}

List<String> tryGenerateVersion(String jdk) {
  try {
    def changelistF = "${pwd tmp: true}/changelist"
    infra.runWithJava(infra.gradleCommand([
      '--no-daemon',
      'cleanTest',
      'generateGitVersion',
      "-PgitVersionFile=${changelistF}",
      "-PgitVersionFormat=rc%d.%s",
      '-PgitVersionSanitize=true'
    ]), jdk)
    def version = readFile(changelistF).readLines()
    // We have a formatted version and a full git hash
    return (version.size() == 2 && version[0] ==~ /(.*-)?(rc[0-9]+\..*)/ && version[1] ==~ /[a-f0-9]{40}/) ? version : null
  } catch (Exception e) {
    echo "Could not generate incremental version, proceeding with non incremental version build."
    return null
  }
}
