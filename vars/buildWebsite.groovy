#!/usr/bin/env groovy

def call(Map params = [:]) {
  final Map defaultConfig = [
    cronPattern: '@daily',
    typosCheck: true,
    lint: true,
    deployFolder: '',
    junitResultsPattern: '',
    customEnvsDevelopment: [],
    customEnvsProduction: [],
    additionalCredentialsIdsAndVars: [:],
    coveragePath: '',
    releaseToNpmFromBranches: [], // only for NPM components
  ]
  final Map config = defaultConfig << params

  // Do not trigger daily if not on the primary branch (e.g. not on PR, not on other branches, not on tags)
  final String cronPattern = env.BRANCH_IS_PRIMARY ? config.cronPattern : ''
  Boolean abortPrevious = true
  int numBuildToKeep = 5
  // Don't abort previous builds and keep more of them on primary or NPM release branches
  if (env.BRANCH_IS_PRIMARY || (config.releaseToNpmFromBranches && config.releaseToNpmFromBranches.contains(env.BRANCH_NAME))) {
    abortPrevious = false
    numBuildToKeep = 20
  }
  properties([
    disableConcurrentBuilds(abortPrevious: abortPrevious),
    buildDiscarder(logRotator(numToKeepStr: numBuildToKeep)),
    pipelineTriggers([cron(cronPattern)]),
  ])

  if (!config.deployFolder) {
    echo 'WARNING: buildWebsite requires a "deployFolder" parameter (e.g. \'public\') for preview and publication'
  }

  int retryCounter = 0
  retry(count: 3, conditions: [kubernetesAgent(handleNonKubernetes: true), nonresumable()]) {
    String agentLabel = infra.getBuildWebsiteAgentLabel(retryCounter)
    retryCounter++
    node(agentLabel) {
      timeout(60) {
        def additionalProductionCredentials = []
        if (!env.CHANGE_ID && !infra.isCiController()) {
          additionalProductionCredentials = config.additionalCredentialsIdsAndVars?.collect { credentialsId, envVarName ->
            string(credentialsId: credentialsId, variable: envVarName)
          } ?: []
        }
        withCredentials(additionalProductionCredentials) {
          // TODO: prevent overrides from custom envs?
          List envVars = ['TZ=UTC', 'NODE_ENV=production'] + config.customEnvsProduction
          // Pull requests
          if (env.CHANGE_ID) {
            envVars = ['TZ=UTC', 'NODE_ENV=development'] + config.customEnvsDevelopment
          }
          withEnv(envVars) {
            Map scripts = [:]
            stage('Checkout') {
              checkout scm
              scripts = getPackageManagerScripts()
            }

            if (config.typosCheck) {
              stage('Typos check') {
                sh 'typos --format json | typos-checkstyle - > typos-checkstyle.xml || true'
                recordIssues(tools: [checkStyle(id: 'typos', name: 'Typos', pattern: 'typos-checkstyle.xml')])
              }
            }

            stage('Dependencies install') {
              sh scripts['install']
            }

            if (config.lint) {
              stage('Lint') {
                try {
                  sh scripts['lint']
                } catch (e) {
                  recordIssues(stopBuild: true, tools: [
                    esLint(pattern: 'eslint-results.json'),
                    checkStyle(pattern: 'eslint.xml'),
                    styleLint(pattern: 'stylelint-results.json'),
                  ])
                }
              }
            }

            stage('Build') {
              infra.maybeWebsitePreBuildCommand()
              sh scripts['build']
            }

            stage('Test') {
              sh scripts['test']
              if (config.junitResultsPattern) {
                junit(testResults: config.junitResultsPattern)
              }
            }

            // cobertura not installed on other controllers than ci.jenkins.io by design
            if (config.coveragePath && infra.isCiController()) {
              stage('Coverage') {
                sh scripts['coverage']
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
              infra.deployWebsite(config.deployFolder)
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
    scripts.findAll { key, value ->
      value.contains('--if-present')
    }.keySet().each { optionalScript ->
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
