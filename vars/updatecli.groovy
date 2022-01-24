def call(userConfig = [:]) {
  def defaultConfig = [
    action: 'diff',
    config: './updatecli/updatecli.d',
    values: './updatecli/values.yaml',
    updatecliDockerImage: 'jenkinsciinfra/helmfile:2.1.8',
    containerMemory: '512Mi'
  ]

  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  def updatecliCommand = 'updatecli ' +  finalConfig.action
  // Do not add the flag "--config" if the provided value is "empty string"
  updatecliCommand += finalConfig.config ? " --config ${finalConfig.config}" : ''
  // Do not add the flag "--values" if the provided value is "empty string"
  updatecliCommand += finalConfig.values ? " --values ${finalConfig.values}" : ''

  // Define a cron trigger only if it's requested by the user through attribute
  properties([
    pipelineTriggers(finalConfig.cronTriggerExpression ? [cron(finalConfig.cronTriggerExpression)] : [])
  ])

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
