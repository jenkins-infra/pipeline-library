/**
 Note: this shared pipeline is tailored to Jenkins infrastructure usage and thus set some default values which might not be desirable for yours.
 **/

def call(userConfig = [:]) {
  def defaultConfig = [
    imageName: '',
    rebuildImageOnPeriodicJob: true,
    updatecliCredentialsId: 'github-app-updatecli-on-jenkins-infra',
    buildDockerAndPublishImageCredentialsId: 'github-app-infra',
    updatecliApplyCronTriggerExpression: '@weekly',
    updatecliConfig: [:],
    buildDockerConfig: [:],
  ]

  if (userConfig.containerMemory) {
    echo 'WARNING: passing the attribute "containerMemory" as top level argument is deprecated. Please use "updatecliConfig: [containerMemory: <value>]" instead.'
    defaultConfig.updatecliConfig.containerMemory = userConfig.containerMemory
  }

  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  if (!finalConfig.imageName && !finalConfig.buildDockerConfig.imageName) {
    echo 'ERROR: no imageName provided.'
    currentBuild.result = 'FAILURE'
    return
  }

  parallel(
      failFast: false,
      'docker-image': {
        // Do not rebuild the image when triggered by a periodic job if the config desactivate them
        if (finalConfig.rebuildImageOnPeriodicJob || (!finalConfig.rebuildImageOnPeriodicJob && !currentBuild.getBuildCauses().contains('hudson.triggers.TimerTrigger'))) {
          // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
          final Map dockerBuildConfig = [automaticSemanticVersioning: true, gitCredentials: finalConfig.buildDockerAndPublishImageCredentialsId] << finalConfig.buildDockerConfig
          buildDockerAndPublishImage(finalConfig.imageName, dockerBuildConfig)
        }
      },
      'updatecli': {
        // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
        Map updatecliConfig = [action: 'diff', credentialsId: finalConfig.updatecliCredentialsId] << finalConfig.updatecliConfig

        updatecli(updatecliConfig)
        if (env.BRANCH_IS_PRIMARY) {
          // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
          updatecliConfig = [action: 'apply', cronTriggerExpression: finalConfig.updatecliApplyCronTriggerExpression, credentialsId: finalConfig.updatecliCredentialsId] << finalConfig.updatecliConfig
          updatecli(updatecliConfig)
        }
      }
      )
}
