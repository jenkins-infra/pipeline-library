/**
 terraform.groovy: Please look at https://github.com/jenkins-infra/shared-tools/tree/main/terraform#jenkins-pipeline for documentation and usage.
 Note: this shared pipeline is tailored to Jenkins infrastructure usage and thus set some default values which might not be desirable for yours.
 **/

def call(userConfig = [:]) {
  def defaultConfig = [
    cronTriggerExpression: '@daily', // Defaults to run once a day (to detect configuration drift)
    stagingCredentials: [], // No custom secrets for staging by default
    productionCredentials: [], // No custom secrets for production by default
    productionBranch: 'main', // Defaults to the principal branch
    agentContainerImage: 'jenkinsciinfra/hashicorp-tools:0.5.13', // Version managed by updatecli
    runTests: false, // Executes the tests provided by the "calling" project, which should provide a tests/Makefile
    runCommonTests: true, // Executes the default test suite from the shared tools repository (terratest)
  ]

  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  // Detects build causes once and for all
  final Boolean isBuildCauseTimer = currentBuild.getBuildCauses().contains('hudson.triggers.TimerTrigger')
  final Boolean isBuildCauseUser = currentBuild.getBuildCauses().contains('hudson.model.Cause$UserIdCause')
  final Boolean isBuildOnChangeRequest = env.CHANGE_ID
  final Boolean isBuildOnProductionBranch = (env.BRANCH_NAME == finalConfig.productionBranch && !isBuildOnChangeRequest)

  Map parallelStages = [failFast: false]

  final String sharedToolsSubDir = '.shared-tools'
  final String makeCliCmd = "make --directory=${sharedToolsSubDir}/terraform/"

  // Only define a cron trigger on the primary branch
  if (isBuildOnProductionBranch && finalConfig.cronTriggerExpression) {
    properties([pipelineTriggers([cron(finalConfig.cronTriggerExpression)])])
  }

  properties([
    // Only run 1 build at a time, on a given branch, to ensure that infrastructure changes are sequentials (easier to audit)
    disableConcurrentBuilds(),
    // Only keep build history for long on the principal branch
    buildDiscarder(logRotator(numToKeepStr: isBuildOnProductionBranch ? '50' : '5')),
  ])

  withEnv([
    // https://www.terraform.io/docs/cli/config/environment-variables.html#tf_in_automation
    'TF_IN_AUTOMATION=1',
    // https://www.terraform.io/docs/cli/config/environment-variables.html#tf_input
    'TF_INPUT=0',
  ]) {

    if (!isBuildCauseUser) {
      parallelStages['staging'] = {
        stage('Staging') {
          agentTemplate(finalConfig.agentContainerImage, {
            withCredentials(finalConfig.stagingCredentials) {
              stage('🔎 Validate Terraform for Staging Environment') {
                getInfraSharedTools(sharedToolsSubDir)

                sh makeCliCmd + ' validate'
              }
              if (finalConfig.runCommonTests) {
                stage('✅ Commons Test Terraform Project') {
                  sh makeCliCmd + ' common-tests'
                }
              }
              if (finalConfig.runTests) {
                stage('✅ Specific Tests Terraform Project') {
                  sh 'make -C ./tests'
                }
              }
            }
          })
        }
      }
    }

    parallelStages['production'] = {
      stage('Production') {
        agentTemplate(finalConfig.agentContainerImage, {
          withCredentials(defaultConfig.productionCredentials) {
            final String planFileName = 'terraform-plan-for-humans.txt'
            def scmOutput
            stage('🦅 Generate Terraform Plan') {
              // When the job is triggered by the daily cron timer, then the plan succeed only if there is no changes found (e.g. no config drift)
              // For all other triggers, the plan succeed either there are changes or not
              String tfCliArsPlan = ''
              if (isBuildCauseTimer) {
                tfCliArsPlan = '-detailed-exitcode'
              }
              withEnv([
                // https://www.terraform.io/docs/cli/config/environment-variables.html#tf_cli_args-and-tf_cli_args_name
                "TF_CLI_ARGS_plan=${tfCliArsPlan}",
                "PLAN_FILE_NAME=${planFileName}",
              ]) {
                scmOutput = getInfraSharedTools(sharedToolsSubDir)

                try {
                  sh makeCliCmd + ' plan'
                }
                finally {
                  archiveArtifacts planFileName
                }
              }
            }

            if (isBuildOnChangeRequest) {
              stage('🗣 Notify User on the PR') {
                final String planFileUrl = "${env.BUILD_URL}artifact/${planFileName}"
                publishChecks name: 'terraform-plan',
                title: 'Terraform plan for this change request',
                text: "The terraform plan for this change request can be found at <${planFileUrl}>.",
                detailsURL: planFileUrl
              }
              stage('💸 Report estimated costs') {
                withCredentials([string(credentialsId: 'infracost-api-key', variable: 'INFRACOST_API_KEY')]) {
                  Boolean commentReport = false
                  Boolean commentComparison = false
                  try {
                    // On AWS we can use the terraform plan to estimate the costs as it doesn't contains most sensible secrets
                    if (scmOutput.GIT_URL.contains('jenkins-infra/aws')) {
                      sh 'terraform show -json tfplan > plan.json'
                      sh 'infracost breakdown --path plan.json --show-skipped --format json --out-file infracost.json'
                      // Also try the experimental HCL method for comparison and upstream contrib
                      sh 'infracost breakdown --path . --terraform-parse-hcl --show-skipped --format json --out-file infracost-hcl.json'
                      commentReport = true
                      commentComparison = true
                    }
                    // On other supported terraform projects, we're using the experimental HCL parser instead
                    // so infracost doesn't need the terraform plan and thus doesn't have access to any sensitive values
                    // As soon as the parser gets out of experimental state, we can use this safer method only
                    if (scmOutput.GIT_URL.contains('jenkins-infra/azure')) {
                      sh 'infracost breakdown --path . --terraform-parse-hcl --show-skipped --format json --out-file infracost.json'
                      commentReport = true
                    }
                    // Convert the report as github comment
                    def githubComment = ''
                    if (commentReport) {
                      sh 'infracost output --path infracost.json --format github-comment --show-skipped --out-file github.md'
                      githubComment = "### Plan parsing method\n${readFile(file: 'github.md')}"
                    }
                    // Compare the outputs of the two methods
                    if (commentComparison) {
                      sh 'infracost output --path infracost-hcl.json --format github-comment --show-skipped --out-file github-hcl.md'
                      githubComment += "\n\n### HCL parsing method\n${readFile(file: 'github-hcl.md')}"
                    }
                    if (githubComment != '') {
                      sh "git log --pretty=%s -1 ${scmOutput.GIT_COMMIT} > git-log.txt"
                      githubComment = "## Report for \"${readFile(file: 'git-log.txt').trim()}\"\n${githubComment}"
                      pullRequest.comment(githubComment)
                    }
                  } catch(e) {
                    echo 'Warning: an error occurred during cost estimation: ' + e.toString()
                  }
                }
              }
            }

            // Only ask for manual approval when the build was manually launched by a human
            // Note that we keep waiting with the current node agent as we want to keep the context
            if (isBuildCauseUser) {
              stage('⏳ Waiting for User Input (Manual Approval)') {
                input message: 'Should we apply these changes to production?', ok: '🚢 Yes, ship it!'
              }
            }
            if (!isBuildCauseTimer && isBuildOnProductionBranch) {
              stage('🚢 Shipping Changes') {
                sh makeCliCmd + ' deploy'
              }
            }
          }
        })
      }
    }

    // Execute parallel stages from the map
    parallel parallelStages
  }
}

def agentTemplate(containerImage, body) {
  podTemplate(
      // Custom YAML definition to enforce no service account token (if Terraform uses kubernetes, it would grant it a wrong access)
      yaml: '''
      apiVersion: v1
      kind: Pod
      spec:
        automountServiceAccountToken: false
    ''',
      // The Docker image here is aimed at "1 container per pod" and is embedding Jenkins agent tooling
      containers: [containerTemplate(name: 'jnlp', image: containerImage)]) {
        node(POD_LABEL) {
          timeout(time: 1, unit: 'HOURS') {
            ansiColor('xterm') {
              body.call()
            }
          }
        }
      }
}


// Retrieves the shared tooling
def getInfraSharedTools(String sharedToolsSubDir) {
  // Checkout the actual project on the same gitref as the Jenkinsfile
  outputs = checkout scm

  // Remove any leftover from developers (normal content or submodule) to avoid injection
  sh 'rm -rf ' + sharedToolsSubDir

  // Retrieve the "legit" shared tooling (should be the same as the submodule but we're never sure enough)
  checkout changelog: false, poll: false,
  scm: [$class: 'GitSCM', branches: [[name: '*/main']],
    extensions: [
      [$class: 'CleanBeforeCheckout', deleteUntrackedNestedRepositories: true],
      [$class: 'RelativeTargetDirectory', relativeTargetDir: sharedToolsSubDir],
      [$class: 'GitSCMStatusChecksExtension', skip: true],
    ], userRemoteConfigs: [[credentialsId: 'github-app-infra', url: 'https://github.com/jenkins-infra/shared-tools.git']]]

  return outputs
}
