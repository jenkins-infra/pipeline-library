/**
 Note: this shared pipeline is tailored to Jenkins infrastructure usage and thus set some default values which might not be desirable for yours.
 **/

def call(userConfig = [:]) {
  def defaultConfig = [
    action: 'diff', // Updatecli subcommand to execute
    config: './updatecli/updatecli.d', // Config manifest used by updatecli (can be a file or a directory)
    values: './updatecli/values.yaml', // Values file used by updatecli
    updatecliDockerImage: 'jenkinsciinfra/helmfile:2.4.66', // Container image to use for running updatecli
    containerMemory: '512Mi', // When using 'updatecliDockerImage', this is the memory limit+request of the container
    cronTriggerExpression: '', // When specified, it enables cron trigger for the calling pipeline
    credentialsId: 'github-app-updatecli-on-jenkins-infra', // githubApp or usernamePassword credentials id to use to get an Access Token. The corresponding populated env vars are USERNAME_VALUE & UPDATECLI_GITHUB_TOKEN
  ]

  // TODO: use isInfra() to set a default githubApp credentials id for infra & for ci

  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  def updatecliCommand = 'updatecli ' +  finalConfig.action
  // Do not add the flag "--config" if the provided value is "empty string"
  updatecliCommand += finalConfig.config ? " --config ${finalConfig.config}" : ''
  // Do not add the flag "--values" if the provided value is "empty string"
  updatecliCommand += finalConfig.values ? " --values ${finalConfig.values}" : ''

  // Define a cron trigger only if requested by the user through attribute
  if (finalConfig.cronTriggerExpression) {
    properties([pipelineTriggers([cron(finalConfig.cronTriggerExpression)])])
  }

  // The podTemplate must define only a single container, named `jnlp`
  // Ref - https://support.cloudbees.com/hc/en-us/articles/360054642231-Considerations-for-Kubernetes-Clients-Connections-when-using-Kubernetes-Plugin
  podTemplate(
      containers: [
        containerTemplate(
        name: 'jnlp',
        image: finalConfig.updatecliDockerImage,
        resourceRequestCpu: '1',
        resourceLimitCpu: '1',
        resourceRequestMemory: finalConfig.containerMemory,
        resourceLimitMemory: finalConfig.containerMemory,
        ),
      ]
      ) {
        node(POD_LABEL) {
          final String updatecliRunStage = "Run updatecli: ${finalConfig.action}"
          boolean runUpdatecli = true
          stage("Check if updatecli folder exists: ${finalConfig.action}") {
            checkout scm
            if (!fileExists('updatecli/')) {
              echo 'WARNING: no updatecli folder.'
              runUpdatecli = false
              org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional(updatecliRunStage)
            }
          }
          stage(updatecliRunStage) {
            if (runUpdatecli) {
              withCredentials([
                usernamePassword(
                credentialsId: finalConfig.credentialsId,
                usernameVariable: 'USERNAME_VALUE', // Setting this variable is mandatory, even if of not used when the credentials is a githubApp one
                passwordVariable: 'UPDATECLI_GITHUB_TOKEN'
                )
              ]) {
                sh 'updatecli version'
                sh updatecliCommand
              } // withCredentials
            } // if (runUpdateCli)
          } // stage
        } // node
      } // podTemplate
}
