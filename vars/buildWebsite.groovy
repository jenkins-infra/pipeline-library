#!/usr/bin/env groovy

def call(Map params = [:]) {
  final Map defaultConfig = [
    websiteName: '', // must be the netlify name
    timeout: 60,
    typosCheck: true,
    publishDir: '',
    customEnvsPreview: '', // TODO or to remove if not really useful
    customEnvsProduction: '', // TODO or to remove if not really useful
    postInstallCommand: '',
  ]
  final Map config = defaultConfig << params
  if (!config.websiteName) {
    error "buildWebsite requires a 'websiteName' parameter (e.g. websiteName: 'contributor-spotlight')"
  }
  if (!config.publishDir) {
    error "buildWebsite requires a 'publishDir' parameter (e.g. publishDir: './public')"
  }

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
          "WEBSITE_NAME=${config.websiteName}",
          "PUBLIC_DIR=${config.publishDir}",
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
          }

          if (config.postInstallCommand) {
            sh config.postInstallCommand
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
            // TODO: add --skip-scripts
            sh 'npm ci'
          }

          stage('Lint') {
            sh 'npm run lint --if-present'
          }

          stage('Test') {
            sh 'npm test --if-present'
            junit(testResults: 'test-results/**/*.xml', allowEmptyResults: true)
          }

          stage('Build') {
            sh 'npm run build'
          }

          if (env.CHANGE_ID && infra.isInfraCiController()) {
            stage('Deploy preview') {
              withCredentials([string(credentialsId: 'netlify-auth-token', variable: 'NETLIFY_AUTH_TOKEN')]) {
                try {
                  sh 'netlify-deploy --draft=true --siteName "${WEBSITE_NAME}" --title "Preview deploy for ${CHANGE_ID}" --alias "deploy-preview-${CHANGE_ID}" -d "${PUBLIC_DIR}"'
                  recordDeployment('jenkins-infra', config.websiteName, pullRequest.head, 'success', "https://deploy-preview-${CHANGE_ID}--${config.websiteName}.netlify.app")
                } catch (e) {
                  echo 'Netlify preview deploy failed, continuing'
                  recordDeployment('jenkins-infra', config.websiteName, pullRequest.head, 'failure', "https://deploy-preview-${CHANGE_ID}--${config.websiteName}.netlify.app")
                }
              }
            }
          }

          if (env.BRANCH_IS_PRIMARY) {
            if (infra.isInfraCiController()) {
              stage('Deploy') {
                infra.withWebsiteFileShare(config.websiteName) {
                  try {
                    sh '''
                    # Synchronize the File Share content
                    set +x
                    azcopy sync \
                      --skip-version-check \
                      --recursive=true \
                      --delete-destination=true \
                      "${PUBLIC_DIR}" "${FILESHARE_SIGNED_URL}"
                    '''
                  } catch (e) {
                    // Only collect azcopy logs when the deployment fails (heavy)
                    sh 'cat /home/jenkins/.azcopy/*.log > azcopy.log'
                    archiveArtifacts 'azcopy.log'
                  }
                }
              }
            }

            stage('Publish build report') {
              publishBuildStatusReport()
            }
          }
        }
      }
    }
  }
}
