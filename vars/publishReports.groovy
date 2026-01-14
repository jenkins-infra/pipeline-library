#!/usr/bin/env groovy

/**
 * Wrapper for publishing reports to the reports host
 *
 * See https://issues.jenkins-ci.org/browse/INFRA-947 for more
 */
def call(List<String> files, Map params = [:]) {
  if (!infra.isTrusted() && !infra.isInfra()) {
    error 'Can only call publishReports from within infra.ci or trusted.ci environment'
  }

  // Sanity check to check if the required tools are installed (or fail fast)
  sh '''
  az version
  azcopy --version
  '''

  Map infraFileShareOptions = [
    fileShare: 'reports-jenkins-io',
    fileShareStorageAccount: 'reportsjenkinsio',
  ]
  // Use an explicit Azure Credential unless caller sets Workload Identity
  if (!params.useWorkloadIdentity) {
    infraFileShareOptions['servicePrincipalCredentialsId'] = 'reports-jenkins-io-azurefile-serviceprincipal'
  }

  infra.withFileShareServicePrincipal(infraFileShareOptions) {
    for(int i = 0; i < files.size(); ++i) {
      String filename = files[i]
      String contentType = ''

      switch (filename) {
        case ~/(?i).*\.html/:
          contentType = 'text/html'
          break
        case ~/(?i).*\.css/:
          contentType = 'text/css'
          break
        case ~/(?i).*\.json/:
          contentType = 'application/json'
          break
        case ~/(?i).*\.js/:
          contentType = 'application/javascript'
          break
        case ~/(?i).*\.gif/:
          contentType = 'image/gif'
          break
        case ~/(?i).*\.png/:
          contentType = 'image/png'
          break
      }

      String[] directory = filename.split("/")
      String basename = directory[directory.size() - 1]
      String dirname = Arrays.copyOfRange(directory, 0, directory.size()-1 ).join("/")

      try {
        withEnv([
          "CONTENT_TYPE=${contentType}",
          "SOURCE_DIRNAME=${dirname ?: '.'}",
          "DESTINATION_PATH=${dirname ?: ''}",
          "PATTERN=${ basename ?: '*' }",
          "IS_CREDENTIAL_LESS=${params.useWorkloadIdentity.toString()}",
        ]) {
          sh '''
          if [[ "${IS_CREDENTIAL_LESS}" == "true" ]]
          then
            # No query string (but a trailing slash in 'FILESHARE_SIGNED_URL')
            fileShareUrl="${FILESHARE_SIGNED_URL}${DESTINATION_PATH}/"
          else
            # Don't output sensitive information such as the SAS token in the querystring
            set +x
            fileShareUrl="$(echo "${FILESHARE_SIGNED_URL}" | sed "s#/?#/${DESTINATION_PATH}?#")"
          fi

          # Synchronize the File Share content
          azcopy copy \
            --skip-version-check \
            --put-md5 `# File length us used by default which can lead to errors for tiny text files` \
            --content-type="${CONTENT_TYPE}" \
            --recursive \
            "${SOURCE_DIRNAME}/${PATTERN}" "${fileShareUrl}"
          '''
        }
      } catch (err) {
        currentBuild.result = 'FAILURE'
        sh '''
              # Retrieve azcopy logs to archive them
              cat $HOME/.azcopy/*.log > azcopy.log 2>/dev/null || echo "No azcopy logs found"
          '''
        archiveArtifacts 'azcopy.log'
        throw err
      }
    }
  }
}
