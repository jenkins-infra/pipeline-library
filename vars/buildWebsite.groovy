#!/usr/bin/env groovy

def call(Map params = [:]) {
  final Map defaultConfig = [
    timeout: 60,
    typosCheck: true,
    lint: true,
    publicFolder: '',
    customEnvsPreview: '', // TODO or to remove if not really useful
    customEnvsProduction: '', // TODO or to remove if not really useful
    preBuildCommand: '',
    coveragePath: '',
    releaseToNpmFromBranches: [], // only for NPM components
  ]
  final Map config = defaultConfig << params
  if (!config.websiteName) {
    error "buildWebsite requires a 'websiteName' parameter (e.g. websiteName: 'contributor-spotlight')"
  }
  if (!config.publicFolder) {
    echo 'WARNING: buildWebsite requires a "publicFolder" parameter (e.g. publicFolder: \'./public\') for preview and publication'
  }
  final String website = config.websiteName

  // Do not trigger daily if not on the primary branch (e.g. not on PR, not on other branches, not on tags)
  final String cronPattern = env.BRANCH_IS_PRIMARY ? '@daily' : ''

  final String nodeEnvironment = env.CHANGE_ID ? 'development' : 'production'
  final String disableSearchEngine = env.CHANGE_ID ? 'true' : 'false'

  properties([
    disableConcurrentBuilds(abortPrevious: true),
    buildDiscarder(logRotator(numToKeepStr: '5')),
    pipelineTriggers([cron(cronPattern)]),
  ])

  int retryCounter = 0
  retry(count: 3, conditions: [kubernetesAgent(handleNonKubernetes: true), nonresumable()]) {
    String agentLabel = infra.getBuildWebsiteAgentLabel(retryCounter)
    retryCounter++
    node(agentLabel) {
      timeout(config.timeout) {
        withEnv([
          "NODE_ENV=${nodeEnvironment}",
          "DISABLE_SEARCH_ENGINE=${disableSearchEngine}",
          'TZ=UTC',
        ]) {
          stage('Checkout') {
            infra.checkoutSCM()
          }

          stage('Sanity checks') {
            echo "Currently running from an agent with label '${agentLabel}'"
            sh 'node --version'
            sh 'npm --version'
            ['.tool-versions', '.nvmrc'].each {
              if (fileExists(it)) {
                echo 'For the record; should be the same as above, update it otherwise'
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
                  styleLint(pattern: 'stylelint-results.json')
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

          // Private section
          if (infra.isInfraCiController()) {
            if (env.CHANGE_ID) {
              stage('Deploy preview') {
                infra.deployWebsitePreview(name: website, publicFolder: config.publicFolder)
              }
            }

            if (env.BRANCH_IS_PRIMARY) {
              stage('Publish') {
                infra.publishWebsite(name: website, publicFolder: config.publicFolder)
              }
            }

            if (releaseToNpmFromBranches.contains(env.BRANCH_NAME)) {
              stage('Release') {
                infra.releaseToNpm(website)
              }
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
