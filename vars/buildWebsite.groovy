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
        // NODE_ENV and TZ=UTC are set by default
        withEnv(infra.getWebsiteEnvVars([developement: config.customEnvsDevelopement, production: config.customEnvsProduction])) {
          stage('Checkout') {
            infra.checkoutSCM()
          }

          stage('Sanity checks') {
            echo "Current config: ${config}"
            echo "Currently running from an agent with label '${agentLabel}'"
            sh 'node --version'
            sh 'npm --version'
            echo '.tool-versions & .nvmc content below for the record (should be the same as above, update them otherwise):'
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
              // TODO: review; on infra.ci:
              // 19:39:39  /home/jenkins/workspace/obs_contributor-spotlight_PR-705@tmp/durable-ca76dd5d/script.sh.copy: line 1: typos-checkstyle: command not found
              // 19:39:40  Broken pipe (os error 32)
              sh 'typos --format json | typos-checkstyle - > typos-checkstyle.xml || true'
              recordIssues(tools: [checkStyle(id: 'typos', name: 'Typos', pattern: 'typos-checkstyle.xml')])
            }
          }

          stage('Install') {
            // if (fileExists('.tool-versions')) {
            //   sh 'asdf install'
            // }
            // TODO: readTrusted(package.json)? Even if incomplete
            // --ignore-scripts is passed by default since summer 2026
            sh 'npm ci'
          }

          if (config.lint) {
            stage('Lint') {
              try {
                sh 'npm run lint --if-present'
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
            sh 'npm run build'
          }

          stage('Test') {
            sh 'npm test --if-present'
            junit(testResults: 'test-results/**/*.xml', allowEmptyResults: true)
            // for jenkins-io-components:
            junit(testResults: 'junit.xml', allowEmptyResults: true)
          }

          // cobertura seems broken on infra, and we don't need to publish it from there
          if (config.coveragePath && !infra.isInfraCiController()) {
            stage('Coverage') {
              sh 'npm run coverage --if-present'
              recordCoverage name: 'coverage', sourceCodeRetention: 'NEVER', tools: [[parser: 'COBERTURA', pattern: config.coveragePath]]
            }
          }

          String deployStage = 'Deploy'
          if (env.CHANGE_ID) { deployStage += ' preview' }
          if (env.BRANCH_IS_PRIMARY) { deployStage += ' production' }
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
