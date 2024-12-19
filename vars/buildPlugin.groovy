#!/usr/bin/env groovy
/**
 * Simple wrapper step for building a plugin
 */
def call(Map params = [:]) {
  properties([
    disableConcurrentBuilds(abortPrevious: true),
    buildDiscarder(logRotator(numToKeepStr: '5')),
  ])

  def repo = params.containsKey('repo') ? params.repo : null
  def failFast = params.containsKey('failFast') ? params.failFast : true
  def timeoutValue = params.containsKey('timeout') ? params.timeout : 60
  def gitDefaultBranch = params.containsKey('gitDefaultBranch') ? params.gitDefaultBranch : null
  def useArtifactCachingProxy = params.containsKey('useArtifactCachingProxy') ? params.useArtifactCachingProxy : true

  def useContainerAgent = params.containsKey('useContainerAgent') ? params.useContainerAgent : false
  def forkCount = params.containsKey('forkCount') ? params.forkCount : null
  if (forkCount) {
    echo "Running parallel tests with forkCount=${forkCount}"
  }
  if (timeoutValue > 180) {
    echo "Timeout value requested was $timeoutValue, lowering to 180 to avoid Jenkins project's resource abusive consumption"
    timeoutValue = 180
  }

  boolean publishingIncrementals = false
  boolean archivedArtifacts = false
  Map tasks = [failFast: failFast]
  getConfigurations(params).each { config ->
    String label = ''
    String platform = config.platform
    String jdk = config.jdk
    String jenkinsVersion = config.jenkins

    String stageIdentifier = "${platform}-${jdk}${jenkinsVersion ? '-' + jenkinsVersion : ''}"
    boolean first = tasks.size() == 1
    boolean skipTests = params?.tests?.skip
    boolean addToolEnv = !useContainerAgent

    if (useContainerAgent) {
      if (platform == 'linux' || platform == 'windows') {
        def agentContainerLabel = jdk == '8' ? 'maven' : 'maven-' + jdk
        if (platform == 'windows') {
          agentContainerLabel += '-windows'
        }
        label = agentContainerLabel
      }
    } else {
      switch(platform) {
        case 'windows':
          label = 'docker-windows'
          break
        case 'linux':
          label = 'vm && linux'
          break
        default:
          echo "WARNING: Unknown Virtual Machine platform '${platform}'. Set useContainerAgent to 'true' unless you want to be in uncharted territory."
          label = platform
      }
    }

    tasks[stageIdentifier] = {
      retry(count: 3, conditions: [kubernetesAgent(handleNonKubernetes: true), nonresumable()]) {
        node(label) {
          try {
            timeout(timeoutValue) {
              // Archive artifacts once with pom declared baseline
              boolean doArchiveArtifacts = !jenkinsVersion && !archivedArtifacts
              if (doArchiveArtifacts) {
                archivedArtifacts = true
              }
              boolean incrementals // cf. JEP-305

              stage("Checkout (${stageIdentifier})") {
                infra.checkoutSCM(repo)
                incrementals = fileExists('.mvn/extensions.xml') &&
                    readFile('.mvn/extensions.xml').contains('git-changelist-maven-extension')
                final String gitUnavailableMessage = '[buildPlugin] Git CLI may not be available'
                withEnv(["GITUNAVAILABLEMESSAGE=${gitUnavailableMessage}"]) {
                  if (incrementals) {
                    // Incrementals needs 'git status -s' to be empty at start of job
                    if (isUnix()) {
                      sh 'git clean -xffd || echo "$GITUNAVAILABLEMESSAGE"'
                    } else {
                      bat 'git clean -xffd || echo %GITUNAVAILABLEMESSAGE%'
                    }
                  }

                  if (gitDefaultBranch) {
                    withEnv(["GITDEFAULTBRANCH=${gitDefaultBranch}"]) {
                      if (isUnix()) {
                        sh 'git config --global init.defaultBranch "$GITDEFAULTBRANCH" || echo "$GITUNAVAILABLEMESSAGE"'
                      } else {
                        bat 'git config --global init.defaultBranch %GITDEFAULTBRANCH% || echo %GITUNAVAILABLEMESSAGE%'
                      }
                    }
                  }
                }
              }

              String changelistF
              String m2repo

              stage("Build (${stageIdentifier})") {
                m2repo = "${pwd tmp: true}/m2repo"
                List<String> mavenOptions = [
                  '--update-snapshots',
                  "-Dmaven.repo.local=$m2repo",
                  '-Dmaven.test.failure.ignore',
                  '-Dspotbugs.failOnError=false',
                  '-Dcheckstyle.failOnViolation=false',
                  '-Dcheckstyle.failsOnError=false',
                  '-Dpmd.failOnViolation=false',
                ]
                // jacoco had file locking issues on Windows, so only running on linux
                if (isUnix()) {
                  mavenOptions += '-Penable-jacoco'
                }
                if (incrementals) {
                  // set changelist and activate produce-incrementals profile
                  mavenOptions += '-Dset.changelist'
                  if (doArchiveArtifacts) {
                    // ask Maven for the value of -rc999.abc123def456
                    changelistF = "${pwd tmp: true}/changelist"
                    mavenOptions += "help:evaluate -Dexpression=changelist -Doutput=$changelistF"
                  }
                }
                if (jenkinsVersion) {
                  mavenOptions += "-Djenkins.version=${jenkinsVersion} -Daccess-modifier-checker.failOnError=false"
                }
                if (skipTests) {
                  mavenOptions += '-DskipTests'
                }
                if (forkCount) {
                  mavenOptions += "-DforkCount=${forkCount}"
                }
                mavenOptions += 'clean install'
                def pit = params?.pit as Map ?: [:]
                def runWithPit = pit.containsKey('skip') && !pit['skip'] // use same convention as in tests.skip
                if (runWithPit && first) {
                  mavenOptions += '-Ppit'
                }
                try {
                  infra.runMaven(mavenOptions, jdk, null, addToolEnv, useArtifactCachingProxy)
                } finally {
                  if (!skipTests) {
                    junit(
                        testResults: '**/target/surefire-reports/**/*.xml,**/target/failsafe-reports/**/*.xml,**/target/invoker-reports/**/*.xml',
                        testDataPublishers: [attachments()],
                        )
                    if (first) {
                      discoverReferenceBuild()
                      // Default configuration for JaCoCo can be overwritten using a `jacoco` parameter (map).
                      // Configuration see: https://www.jenkins.io/doc/pipeline/steps/code-coverage-api/#recordcoverage-record-code-coverage-results
                      Map jacocoArguments = [tools: [[parser: 'JACOCO', pattern: '**/jacoco/jacoco.xml']], sourceCodeRetention: 'MODIFIED']
                      if (params?.jacoco) {
                        jacocoArguments.putAll(params.jacoco as Map)
                      }
                      recordCoverage jacocoArguments
                      if (pit) {
                        Map pitArguments = [tools: [[parser: 'PIT', pattern: '**/pit-reports/mutations.xml']], id: 'pit', name: 'Mutation Coverage', sourceCodeRetention: 'MODIFIED']
                        pitArguments.putAll(pit)
                        pitArguments.remove('skip')
                        recordCoverage(pitArguments)
                      }
                    }
                  }
                }
              }

              stage("Archive (${stageIdentifier})") {
                if (failFast && currentBuild.result == 'UNSTABLE') {
                  error 'There were test failures; halting early'
                }

                if (first) {
                  if (skipTests) {
                    // otherwise the reference build has been computed already
                    discoverReferenceBuild()
                  }
                  echo "Recording static analysis results on '${stageIdentifier}'"

                  recordIssues(
                      enabledForFailure: true,
                      tool: mavenConsole(),
                      skipBlames: true,
                      trendChartType: 'TOOLS_ONLY'
                      )
                  recordIssues(
                      qualityGates: [[threshold: 1, type: 'TOTAL', unstable: true]],
                      tools: [esLint(pattern: '**/target/eslint-warnings.xml')],
                      enabledForFailure: true,
                      sourceCodeEncoding: 'UTF-8',
                      skipBlames: true,
                      trendChartType: 'TOOLS_ONLY')
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
                  Map spotbugsArguments = [tool: spotBugs(pattern: '**/target/spotbugsXml.xml,**/target/findbugsXml.xml'),
                    sourceCodeEncoding: 'UTF-8',
                    skipBlames: true,
                    trendChartType: 'TOOLS_ONLY',
                    qualityGates: [[threshold: 1, type: 'NEW', unstable: true]]]
                  if (params?.spotbugs) {
                    spotbugsArguments.putAll(params.spotbugs as Map)
                  }
                  recordIssues spotbugsArguments

                  Map checkstyleArguments = [tool: checkStyle(pattern: '**/target/**/checkstyle-result.xml'),
                    sourceCodeEncoding: 'UTF-8',
                    skipBlames: true,
                    trendChartType: 'TOOLS_ONLY',
                    qualityGates: [[threshold: 1, type: 'TOTAL', unstable: true]]]
                  if (params?.checkstyle) {
                    checkstyleArguments.putAll(params.checkstyle as Map)
                  }
                  recordIssues checkstyleArguments

                  Map pmdArguments = [tool: pmdParser(pattern: '**/target/**/pmd.xml'),
                    sourceCodeEncoding: 'UTF-8',
                    skipBlames: true,
                    trendChartType: 'NONE']
                  if (params?.pmd) {
                    pmdArguments.putAll(params.pmd as Map)
                  }
                  recordIssues pmdArguments

                  Map cpdArguments = [tool: cpd(pattern: '**/target/**/cpd.xml'),
                    sourceCodeEncoding: 'UTF-8',
                    skipBlames: true,
                    trendChartType: 'NONE']
                  if (params?.cpd) {
                    cpdArguments.putAll(params.cpd as Map)
                  }
                  recordIssues cpdArguments

                  recordIssues(
                      enabledForFailure: true, tool: taskScanner(
                      includePattern:'**/*.java',
                      excludePattern:'**/target/**',
                      highTags:'FIXME',
                      normalTags:'TODO'),
                      sourceCodeEncoding: 'UTF-8',
                      skipBlames: true,
                      trendChartType: 'NONE'
                      )
                  if (failFast && currentBuild.result == 'UNSTABLE') {
                    error 'Static analysis quality gates not passed; halting early'
                  }
                  /*
                   * If the current build was successful, we send the commits to Launchable so that
                   * the result can be consumed by a Launchable build in the future. We do not
                   * attempt to record commits for non-incrementalified plugins because such
                   * plugins' PR builds could not be consumed by anything else anyway, and all
                   * plugins currently in the BOM are incrementalified.
                   */
                  if (incrementals && currentBuild.currentResult == 'SUCCESS' && useContainerAgent) {
                    withCredentials([string(credentialsId: 'launchable-jenkins-bom', variable: 'LAUNCHABLE_TOKEN')]) {
                      if (isUnix()) {
                        sh 'launchable verify && launchable record commit'
                      } else {
                        bat 'launchable verify && launchable record commit'
                      }
                    }
                  }
                } else {
                  echo "Skipping static analysis results for ${stageIdentifier}"
                }
                if (doArchiveArtifacts) {
                  if (incrementals) {
                    String changelist = readFile(changelistF)
                    dir(m2repo) {
                      fingerprint '**/*-rc*.*/*-rc*.*' // includes any incrementals consumed
                      archiveArtifacts artifacts: "**/*$changelist/*$changelist*",
                      excludes: '**/*.lastUpdated',
                      allowEmptyArchive: true // in case we forgot to reincrementalify
                    }
                    publishingIncrementals = true
                  } else {
                    archiveArtifacts artifacts: '**/target/*.hpi,**/target/*.jpi,**/target/*.jar', fingerprint: true
                  }
                }
              }
            }
          } finally {
            if (hasDockerLabel()) {
              if (isUnix()) {
                sh 'docker system prune --force --all || echo "Failed to cleanup docker images"'
              } else {
                bat 'docker system prune --force --all || echo "Failed to cleanup docker images"'
              }
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

private void discoverReferenceBuild() {
  folders = env.JOB_NAME.split('/')
  if (folders.length > 1) {
    discoverGitReferenceBuild(scm: folders[1])
  }
}

boolean hasDockerLabel() {
  env.NODE_LABELS?.contains('docker')
}

List<Map<String, String>> getConfigurations(Map params) {
  boolean explicit = params.containsKey('configurations') && params.configurations != null
  boolean implicit = params.containsKey('platforms') || params.containsKey('jdkVersions') || params.containsKey('jenkinsVersions')

  if (explicit && implicit) {
    error '"configurations" option can not be used with either "platforms", "jdkVersions" or "jenkinsVersions"'
  }

  def configs = params.configurations
  configs.each { c ->
    if (!c.platform) {
      error("Configuration field \"platform\" must be specified: $c")
    }
    if (!c.jdk) {
      error("Configuration field \"jdk\" must be specified: $c")
    }
  }

  if (explicit) {
    return params.configurations
  }

  def platforms = params.containsKey('platforms') ? params.platforms : ['linux', 'windows']
  def jdkVersions = params.containsKey('jdkVersions') ? params.jdkVersions : ['8']
  def jenkinsVersions = params.containsKey('jenkinsVersions') ? params.jenkinsVersions : [null]

  def ret = []
  for (p in platforms) {
    for (jdk in jdkVersions) {
      for (jenkins in jenkinsVersions) {
        ret << [
          'platform': p,
          'jdk': jdk,
          'jenkins': jenkins,
        ]
      }
    }
  }
  return ret
}
