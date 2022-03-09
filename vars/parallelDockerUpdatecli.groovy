/**
**/
def call(userConfig = [:]) {
  def defaultConfig = [
    imageName: '',
    rebuildImageOnPeriodicJob: true,
    credentialsId: 'updatecli-github-token',
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

  if (!finalConfig.imageName) {
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
        final Map dockerBuildConfig = [automaticSemanticVersioning: true, gitCredentials: 'github-app-infra'] << finalConfig.buildDockerConfig
        buildDockerAndPublishImage(finalConfig.imageName, dockerBuildConfig)
      }
    },
    'updatecli': {
      withCredentials([string(credentialsId: finalConfig.credentialsId,variable: 'UPDATECLI_GITHUB_TOKEN')]) {
        // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
        Map updatecliConfig = finalConfig.updatecliConfig << [action: 'diff']

        updatecli(updatecliConfig)
        if (env.BRANCH_IS_PRIMARY) {
          // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
          updatecliConfig = finalConfig.updatecliConfig << [action: 'apply', cronTriggerExpression: finalConfig.updatecliApplyCronTriggerExpression]
          updatecli(updatecliConfig)
        }
      }
    }
  )
}
