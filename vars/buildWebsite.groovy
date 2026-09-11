#!/usr/bin/env groovy

def call(Map params = [:]) {
  final Map defaultConfig = [
    cronPattern: '@daily',
    timeout: 60,
    typosCheck: true,
    lint: true,
    publicFolder: '',
    customEnvsDevelopement: [],
    customEnvsProduction: [],
    preBuildCommand: '',
    coveragePath: '',
    releaseToNpmFromBranches: [], // only for NPM components
  ]
  final Map config = defaultConfig << params

  // Do not trigger daily if not on the primary branch (e.g. not on PR, not on other branches, not on tags)
  final String cronPattern = env.BRANCH_IS_PRIMARY ? config.cronPattern : ''
  properties([
    disableConcurrentBuilds(abortPrevious: true),
    buildDiscarder(logRotator(numToKeepStr: '5')),
    pipelineTriggers([cron(cronPattern)]),
  ])

  if (!config.publicFolder) {
    echo 'WARNING: buildWebsite requires a "publicFolder" parameter (e.g. \'public\') for preview and publication'
  }

  int retryCounter = 0
  retry(count: 3, conditions: [kubernetesAgent(handleNonKubernetes: true), nonresumable()]) {
    String agentLabel = infra.getBuildWebsiteAgentLabel(retryCounter)
    retryCounter++
    node(agentLabel) {
      timeout(config.timeout) {
        withEnv(infra.getWebsiteEnvVars([
          developement: config.customEnvsDevelopement,
          production: config.customEnvsProduction
        ])) {
          Map packageManagerScripts = [:]
          stage('Checkout') {
            infra.checkoutSCM()
            packageManagerScripts = getPackageManagerScripts()
          }

          stage('Sanity checks') {
            echo "Current config: ${config}"
            echo "Currently running from an agent with label '${agentLabel}'"
            echo "Available scripts: ${packageManagerScripts}"
            sh 'node --version'
            sh packageManagerScripts['version']
            echo '.tool-versions & .nvmc content below for the record:'
            ['.tool-versions', '.nvmrc'].each {
              if (fileExists(it)) {
                withEnv(["FILE_TO_CAT=${it}"]) {
                  sh 'cat "${FILE_TO_CAT}"'
                }
              }
            }
          }

          if (config.typosCheck) {
            stage('Typos check') {
              sh 'typos --format json | typos-checkstyle - > typos-checkstyle.xml || true'
              recordIssues(tools: [checkStyle(id: 'typos', name: 'Typos', pattern: 'typos-checkstyle.xml')])
            }
          }

          stage('Install') {
            sh packageManagerScripts['install']
          }

          if (config.lint) {
            stage('Lint') {
              try {
                sh packageManagerScripts['lint']
              } catch (e) {
                recordIssues(stopBuild: true, tools: [
                  esLint(pattern: 'eslint-results.json'),
                  checkStyle(pattern: 'eslint.xml'),
                  styleLint(pattern: 'stylelint-results.json'),
                ])
              }
            }
          }

          if (config.preBuildCommand) {
            sh config.preBuildCommand
          }

          stage('Build') {
            sh packageManagerScripts['build']
          }

          stage('Test') {
            sh packageManagerScripts['test']
            junit(testResults: 'test-results/**/*.xml', allowEmptyResults: true)
            junit(testResults: 'junit.xml', allowEmptyResults: true) // for jenkins-io-components
          }

          // cobertura seems broken on infra, and we don't need to publish it from there
          if (config.coveragePath && !infra.isInfraCiController()) {
            stage('Coverage') {
              sh packageManagerScripts['coverage']
              recordCoverage name: 'coverage', sourceCodeRetention: 'NEVER', tools: [[parser: 'COBERTURA', pattern: config.coveragePath]]
            }
          }

          String deployStage = 'Deploy'
          if (env.CHANGE_ID) {
            deployStage += ' preview'
          }
          if (env.BRANCH_IS_PRIMARY) {
            deployStage += ' production'
          }
          stage(deployStage) {
            // Skip on ci.jenkins.io
            infra.deployWebsite(config.publicFolder)
          }

          if (config.releaseToNpmFromBranches.contains(env.BRANCH_NAME)) {
            stage('Release') {
              // Skip on ci.jenkins.io
              infra.releaseToNpm()
            }
          }

          if (env.BRANCH_IS_PRIMARY) {
            stage('Publish build report') {
              publishBuildStatusReport()
            }
          }
        }
      }
    }
  }
}

// Must be called after checkout
Map getPackageManagerScripts() {
  final String packageManager = fileExists('yarn.lock') ? 'yarn' : 'npm'
  echo "Package manager determined by checking if yarn.lock exists or not: ${packageManager}"
  // Default (npm) scripts
  Map scripts = [
    '': 'echo "No script passed" && exit 1',
    'version': "${packageManager} --version",
    'install': "${packageManager} ci",
    'build': "${packageManager} run build",
    'lint': "${packageManager} run lint --if-present",
    'test': "${packageManager} run test --if-present",
    'coverage': "${packageManager} run coverage --if-present",
  ]
  // Specific yarn scripts
  if (packageManager == 'yarn') {
    // Equivalent of npm ci
    scripts['install'] = 'yarn install --immutable'

    // Optional scripts (yarn doesn't have any "--if-present" equivalent)
    scripts.findAll { key, value -> value.contains('--if-present') }.keySet().each { optionalScript ->
      withEnv(["OPTIONAL_SCRIPT=${optionalScript}"]) {
        Boolean exist = sh(
        script: 'node -e "process.exit(require(\'./package.json\').scripts?.${OPTIONAL_SCRIPT} ? 0 : 1)"',
        returnStatus: true
        ) == 0
        scripts[optionalScript] = exist ? 'yarn ' + optionalScript : 'echo "Optional script not found in package.json: ' + optionalScript
      }
    }
  }
  return scripts
}
