
def call(Map userConfig = [:], Closure body) {
  def defaultConfig = [
    retries: 3,
    bodyTimeout: 10,
    bodyTimeoutUnit: 'MINUTES',
  ]

  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  def counter = 1

  // From https://issues.jenkins.io/browse/JENKINS-51454?focusedCommentId=389893&page=com.atlassian.jira.plugin.system.issuetabpanels%3Acomment-tabpanel#comment-389893
  retry(finalConfig.retries) {
    try {
      timeout(time: finalConfig.bodyTimeout, unit: finalConfig.bodyTimeoutUnit) {
        withEnv(["PIPELINE_RETRY_COUNTER=${counter}"]) {
          body()
        }
      }
    } catch(org.jenkinsci.plugins.workflow.steps.FlowInterruptedException ex){
      def causes = ex.causes
      if(causes.find { ! (it instanceof org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution$ExceededTimeout) }) {
        throw ex
      }
      throw new RuntimeException(ex)
      counter += 1
    }
  }
}
