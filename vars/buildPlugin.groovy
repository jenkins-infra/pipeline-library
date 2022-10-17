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
  def artifactCachingProxyEnabled = params.containsKey('artifactCachingProxyEnabled') ? params.artifactCachingProxyEnabled : false
  def additionalAgentLabels = params.containsKey('additionalAgentLabels') ? params.additionalAgentLabels : null

  def useContainerAgent = params.containsKey('useContainerAgent') ? params.useContainerAgent : false
  if (params.containsKey('useAci')) {
    infra.publishDeprecationCheck('Replace useAci with useContainerAgent', 'The parameter "useAci" is deprecated. Please use "useContainerAgent" instead as per https://issues.jenkins.io/browse/INFRA-2918.')
    useContainerAgent = params.containsKey('useAci')
  }
  if (timeoutValue > 180) {
    echo "Timeout value requested was $timeoutValue, lowering to 180 to avoid Jenkins project's resource abusive consumption"
    timeoutValue = 180
  }

  boolean publishingIncrementals = false
  boolean archivedArtifacts = false
  Map tasks = [failFast: failFast]
  getConfigurations(params).each { config ->
    String label = config.platform
    String jdk = config.jdk
    String jenkinsVersion = config.jenkins
    if (config.containsKey('javaLevel')) {
      infra.publishDeprecationCheck('Remove javaLevel', 'Ignoring deprecated "javaLevel" parameter. This parameter should be removed from your "Jenkinsfile".')
    }

    String stageIdentifier = "${label}-${jdk}${jenkinsVersion ? '-' + jenkinsVersion : ''}"
    boolean first = tasks.size() == 1
    boolean skipTests = params?.tests?.skip
    boolean addToolEnv = !useContainerAgent

    if (useContainerAgent && (label == 'linux' || label == 'windows')) {
      def agentContainerLabel = jdk == '8' ? 'maven' : 'maven-' + jdk
      if (label == 'windows') {
        agentContainerLabel += '-windows'
      }
      label = agentContainerLabel
    }

    // DEBUG: concatenate passed labels to be able to test specific artifact caching providers (ex: " && doks")
    if (additionalAgentLabels != null && additionalAgentLabels != '') {
      label += additionalAgentLabels
    }

    tasks[stageIdentifier] = {
      retry(count: 3, conditions: [kubernetesAgent(handleNonKubernetes: true), nonresumable()]) {
        node(label) {
          try {
            timeout(timeoutValue) {
              boolean isMaven
              // Archive artifacts once with pom declared baseline
              boolean doArchiveArtifacts = !jenkinsVersion && !archivedArtifacts
              if (doArchiveArtifacts) {
                archivedArtifacts = true
              }
              boolean incrementals // cf. JEP-305

              stage("Checkout (${stageIdentifier})") {
                infra.checkoutSCM(repo)
                isMaven = !fileExists('gradlew')
                incrementals = fileExists('.mvn/extensions.xml') &&
                    readFile('.mvn/extensions.xml').contains('git-changelist-maven-extension')
                final String gitUnavailableMessage = '[buildPlugin] Git CLI may not be available'
                withEnv(["GITUNAVAILABLEMESSAGE=${gitUnavailableMessage}"]) {
                  if (incrementals) { // Incrementals needs 'git status -s' to be empty at start of job
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
                String command
                if (isMaven) {
                  m2repo = "${pwd tmp: true}/m2repo"
                  List<String> mavenOptions = [
                    '--update-snapshots',
                    "-Dmaven.repo.local=$m2repo",
                    '-Dmaven.test.failure.ignore',
                    '-Dspotbugs.failOnError=false',
                    '-Dcheckstyle.failOnViolation=false',
                    '-Dcheckstyle.failsOnError=false',
                  ]
                  def settingsFile = ''
                  final String defaultProxyProvider = 'azure'
                  def availableProxyProviders = ['azure', 'do', 'aws']
                  String requestedProvider = env.ARTIFACT_CACHING_PROXY_PROVIDER
                  // As azure VM agents don't have this env var, setting a default provider if none is specified or if the provider isn't available
                  if (requestedProvider == null || requestedProvider == '' || !availableProxyProviders.contains(requestedProvider)) {
                    requestedProvider = defaultProxyProvider
                    echo "INFO: no valid artifact caching proxy provider specified, set to '$defaultProxyProvider' by default."
                  }
                  if (artifactCachingProxyEnabled && availableProxyProviders.contains(requestedProvider)) {
                    // Add encrypted password to the settings
                    // See steps at https://maven.apache.org/guides/mini/guide-encryption.html
                    withCredentials([
                      usernamePassword(credentialsId: 'artifact-caching-proxy-credentials', usernameVariable: 'ARTIFACT_CACHING_PROXY_USERNAME', passwordVariable: 'ARTIFACT_CACHING_PROXY_PASSWORD')
                    ]) {
                      echo "Setting up maven to use artifact caching proxy from '${requestedProvider}' provider..."
                      settingsFile = "${m2repo}/settings.xml"
                      final String settingsSecurityFile = "${m2repo}/settings-security.xml"
                      String mavenSettings = libraryResource 'artifact-caching-proxy/settings.xml'
                      String mavenSettingsSecurity = libraryResource 'artifact-caching-proxy/settings-security.xml'
                      String masterPassword
                      String serverPassword
                      if (isUnix()) {
                        masterPassword = sh(script: 'set +x; mvn --encrypt-master-password $(openssl rand -hex 12)', returnStdout: true)
                      } else {
                        masterPassword = bat(script: 'mvn --encrypt-master-password %random%%random%%random%', returnStdout: true)
                      }
                      mavenSettingsSecurity = mavenSettingsSecurity.replace('ENCRYPTED-MASTER-PASSWORD', masterPassword)
                      writeFile file: settingsSecurityFile, text: mavenSettingsSecurity
                      if (isUnix()) {
                        sh "mkdir -p ${HOME}/.m2 && mv ${settingsSecurityFile} ${HOME}/.m2/settings-security.xml"
                        serverPassword = sh(script: 'mvn --encrypt-password $ARTIFACT_CACHING_PROXY_PASSWORD', returnStdout: true)
                      } else {
                        final String settingsSecurityFileWindows = settingsSecurityFile.replace('/', '\\')
                        bat 'mvn --version'
                        bat 'set'
                        settingsFile = env.USERPROFILE + '\\.m2\\settings.xml'
                        echo settingsFile
                        bat "mkdir %userprofile%\\.m2 >nul 2>&1 || move ${settingsSecurityFileWindows} %userprofile%\\.m2\\settings-security.xml"
                        serverPassword = bat(script: 'mvn --encrypt-password $ARTIFACT_CACHING_PROXY_PASSWORD', returnStdout: true)
                      }

                      mavenSettings = mavenSettings.replace('PROVIDER', requestedProvider)
                      mavenSettings = mavenSettings.replace('SERVER-USERNAME', env.ARTIFACT_CACHING_PROXY_USERNAME)
                      mavenSettings = mavenSettings.replace('ENCRYPTED-SERVER-PASSWORD', serverPassword)

                      writeFile file: settingsFile, text: mavenSettings
                      echo "INFO: using artifact caching proxy from '${requestedProvider}' provider"

                      // DEBUG
                      if (isUnix()) {
                        echo 'isUnix'
                      } else {
                        bat 'mvn -X clean'
                      }
                    }
                  } else {
                    echo "WARNING: artifacts will be downloaded directly from https://repo.jenkins-ci.org without using the artifact caching proxy."
                  }
                  // jacoco had file locking issues on Windows, so only running on linux
                  if (isUnix()) {
                    mavenOptions += '-Penable-jacoco'
                  }
                  if (incrementals) { // set changelist and activate produce-incrementals profile
                    mavenOptions += '-Dset.changelist'
                    if (doArchiveArtifacts) { // ask Maven for the value of -rc999.abc123def456
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
                  mavenOptions += 'clean install'
                  try {
                    infra.runMaven(mavenOptions, jdk, null, settingsFile, addToolEnv)
                  } finally {
                    if (!skipTests) {
                      junit('**/target/surefire-reports/**/*.xml,**/target/failsafe-reports/**/*.xml,**/target/invoker-reports/**/*.xml')
                      if (first) {
                        discoverReferenceBuild()
                        publishCoverage calculateDiffForChangeRequests: true, adapters: [jacocoAdapter('**/target/site/jacoco/jacoco.xml')]
                      }
                    }
                  }
                } else {
                  infra.publishDeprecationCheck('Replace buildPlugin with buildPluginWithGradle', 'Gradle mode for buildPlugin() is deprecated, please use buildPluginWithGradle()')
                  List<String> gradleOptions = [
                    '--no-daemon',
                    'cleanTest',
                    'build',
                  ]
                  if (skipTests) {
                    gradleOptions += '--exclude-task test'
                  }
                  command = "gradlew ${gradleOptions.join(' ')}"
                  if (isUnix()) {
                    command = './' + command
                  }

                  try {
                    infra.runWithJava(command, jdk, null, addToolEnv)
                  } finally {
                    if (!skipTests) {
                      junit('**/build/test-results/**/*.xml')
                    }
                  }
                }
              }

              stage("Archive (${stageIdentifier})") {
                if (failFast && currentBuild.result == 'UNSTABLE') {
                  error 'There were test failures; halting early'
                }

                if (first) {
                  if (skipTests) { // otherwise the reference build has been computed already
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

                  Map checkstyleArguments = [tool: checkStyle(pattern: '**/target/checkstyle-result.xml'),
                    sourceCodeEncoding: 'UTF-8',
                    skipBlames: true,
                    trendChartType: 'TOOLS_ONLY',
                    qualityGates: [[threshold: 1, type: 'TOTAL', unstable: true]]]
                  if (params?.checkstyle) {
                    checkstyleArguments.putAll(params.checkstyle as Map)
                  }
                  recordIssues checkstyleArguments

                  Map pmdArguments = [tool: pmdParser(pattern: '**/target/pmd.xml'),
                    sourceCodeEncoding: 'UTF-8',
                    skipBlames: true,
                    trendChartType: 'NONE']
                  if (params?.pmd) {
                    pmdArguments.putAll(params.pmd as Map)
                  }
                  recordIssues pmdArguments

                  Map cpdArguments = [tool: cpd(pattern: '**/target/cpd.xml'),
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
                    String artifacts
                    if (isMaven) {
                      artifacts = '**/target/*.hpi,**/target/*.jpi,**/target/*.jar'
                    } else {
                      artifacts = '**/build/libs/*.hpi,**/build/libs/*.jpi'
                    }
                    archiveArtifacts artifacts: artifacts, fingerprint: true
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

/**
 * @deprecated no longer recommended
 */
static List<Map<String, String>> recommendedConfigurations() {
  null
}
