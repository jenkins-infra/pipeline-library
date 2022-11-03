def call(owner, repo, ref, status, environmentURL, Map userConfig=[:]) {
  def defaultConfig = [
    environment: "preview",
    description: "Deploy to preview environment",
    credentialsId: "github-app-infra"
  ]

  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  withCredentials([
    usernamePassword(credentialsId: finalConfig.credentialsId, usernameVariable: 'GITHUB_APP', passwordVariable: 'GH_TOKEN')
  ]) {
    withEnv([
      "STATUS=${status}",
      "REF=${ref}",
      "ENVIRONMENT=${finalConfig.environment}",
      "DESCRIPTION=${finalConfig.description}",
      "TRANSIENT_ENVIRONMENT=${finalConfig.environment != "production" ? "true" : "false"}",
      "LOG_URL=${BUILD_URL}console",
      "ENVIRONMENT_URL=${environmentURL}",
      "OWNER=${owner}",
      "REPO=${repo}",
    ]) {
      sh('''
          ID=$(jq --null-input \
            --arg ref "$REF" \
            --arg environment "$ENVIRONMENT" \
            --arg description "$DESCRIPTION" \
            --argjson transient_environment $TRANSIENT_ENVIRONMENT \
          '{"ref": $ref, "environment": $environment, "description": $description, "required_contexts": [], "auto_merge": false, "auto_inactive": false, "transient_environment": $transient_environment }' | \
          gh api repos/${OWNER}/${REPO}/deployments \
          -X POST \
          --jq '.id' \
          --input -)

          if [ -z "$ID" ]; then
            echo "Unable to create a deployment"
            exit 1
          fi

          jq --null-input \
            --arg status "$STATUS" \
            --arg environment "$ENVIRONMENT" \
            --arg description "$DESCRIPTION" \
            --arg log_url "$LOG_URL" \
            --arg environment_url "$ENVIRONMENT_URL" \
            '{"state": $status, "environment": $environment, "description": $description, "log_url": $log_url, "environment_url": $environment_url }' | \
            gh api repos/${OWNER}/${REPO}/deployments/${ID}/statuses -X POST --input -
        ''')
    }
  }
}
