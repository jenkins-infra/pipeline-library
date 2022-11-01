import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def call(owner, repo, ref, status, environmentURL, Map userConfig=[:]) {
  def defaultConfig = [
    environment: "preview",
    description: "Deploy to preview environment",
    credentialId: "github-app-infra"
  ]

  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  withCredentials([usernamePassword(credentialsId: finalConfig.credentialsId, usernameVariable: 'GITHUB_APP', passwordVariable: 'GH_TOKEN')]) {
    def jsonSlurper = new JsonSlurper()

    def json = JsonOutput.toJson(
        "ref": ref,
        "environment": finalConfig.environment,
        "description": finalConfig.description,
        "required_contexts": [],
        "auto_merge": false,
        "auto_inactive": false,
        "transient_environment": finalConfig.environment != "production",
    ])
    def object = jsonSlurper.parseText(sh(script: "gh api repos/${owner}/${repo}/deployments  -X POST --input - << EOF\n${json}\nEOF", returnStdout: true).trim()).id
    if (id == ''){
      error('Unable to create deployment')
    }
    def json = JsonOutput.toJson(
        "state": status,
        "environment": finalConfig.environment,
        "description": finalConfig.description,
        "log_url": "${BUILD_URL}console",
        "environment_url": environmentURL,
    ])
    sh("gh api repos/${owner}/${repo}/deployments/${id}/statuses  -X POST --input - << EOF\n${json}\nEOF")
  }
}
