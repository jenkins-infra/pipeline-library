def call(userConfig = [:]) {
  def defaultConfig = [
    action: 'diff', // Updatecli subcommand to execute
    config: './updatecli/updatecli.d', // Config manifest used by updatecli (can be a file or a directory)
    values: './updatecli/values.yaml', // Values file used by updatecli
    updatecliDockerImage: 'jenkinsciinfra/helmfile:2.3.0', // Container image to use for running updatecli
    containerMemory: '512Mi', // When using 'updatecliDockerImage', this is the memory limit+request of the container
    cronTriggerExpression: '', // When specified, it enables cron trigger for the calling pipeline
  ]

  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  def updatecliCommand = 'updatecli ' +  finalConfig.action
  // Do not add the flag "--config" if the provided value is "empty string"
  updatecliCommand += finalConfig.config ? " --config ${finalConfig.config}" : ''
  // Do not add the flag "--values" if the provided value is "empty string"
  updatecliCommand += finalConfig.values ? " --values ${finalConfig.values}" : ''

  // Define a cron trigger only if requested by the user through attribute
  if (finalConfig.cronTriggerExpression) {
    properties([
      pipelineTriggers([
        cron(finalConfig.cronTriggerExpression)
      ])
    ])
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
          sh 'updatecli version'
          sh updatecliCommand
        }
      }// stage
    } // node
  } // podTemplate
}
