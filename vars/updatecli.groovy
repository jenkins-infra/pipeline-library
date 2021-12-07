import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

def call(userConfig = [:]) {
  def defaultConfig = [
    action: 'diff',
    config: './updatecli/updatecli.d',
    values: './updatecli/values.yaml',
    updatecliDockerImage: 'ghcr.io/updatecli/updatecli:v0.16.1',
    containerMemory: '128Mi'
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

  podTemplate(
    containers: [
      containerTemplate(
        name: 'updatecli',
        image: finalConfig.updatecliDockerImage,
        command: 'cat',
        ttyEnabled: true,
        resourceRequestCpu: '200m',
        resourceLimitCpu: '200m',
        resourceRequestMemory: finalConfig.containerMemory,
        resourceLimitMemory: finalConfig.containerMemory,
      ),
    ]
  ) {
    node(POD_LABEL) {
      container('updatecli') {
        stage("Check if updatecli folder exists: ${finalConfig.action}") {
          checkout scm
          if (!fileExists('/updatecli/')) {
            Utils.markStageSkippedForConditional("Run updatecli: ${finalConfig.action}")
          }
        }
        stage("Run updatecli: ${finalConfig.action}") {
          steps {
            sh 'updatecli version'
            sh updatecliCommand
          }
        }// stage
      } // container
    } // node
  } // podTemplate
}
