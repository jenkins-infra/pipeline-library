def call(userConfig = [:]) {
  def defaultConfig = [
    imageName: '',
    mainBranch: 'main',
    rebuildImageOnPeriodicJob: true,
    cronTriggerExpression: '@weekly',
    containerMemory: '128Mi',
    credentialsId: 'updatecli-github-token'
  ]

  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  if (finalConfig.imageName == '') {
    echo 'ERROR: no imageName provided.'
    currentBuild.result = 'FAILURE'
    return
  }
  parallel(
    failFast: false,
    'docker-image': {
      // Do not rebuild the image when triggered by a periodic job if the config desactivate them
      if (finalConfig.rebuildImageOnPeriodicJob || (!finalConfig.rebuildImageOnPeriodicJob && !currentBuild.getBuildCauses().contains('hudson.triggers.TimerTrigger'))) {
        buildDockerAndPublishImage(finalConfig.imageName, [
          mainBranch: finalConfig.mainBranch,
          automaticSemanticVersioning: true,
          gitCredentials: 'github-app-infra'
        ])
      }
    },
    'updatecli': {
      withCredentials([string(credentialsId: finalConfig.credentialsId,variable: 'UPDATECLI_GITHUB_TOKEN')]) {
        updatecli(action: 'diff', containerMemory: finalConfig.containerMemory)
        if (env.BRANCH_IS_PRIMARY) {
          updatecli(action: 'apply', cronTriggerExpression: finalConfig.cronTriggerExpression, containerMemory: finalConfig.containerMemory)
        }
      }
    }
  )
}
