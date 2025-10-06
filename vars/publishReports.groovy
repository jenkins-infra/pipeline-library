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

  infra.withFileShareServicePrincipal([
    servicePrincipalCredentialsId: 'reports-jenkins-io-azurefile-serviceprincipal',
    fileShare: 'reports-jenkins-io',
    fileShareStorageAccount: 'reportsjenkinsio'
  ]) {
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

      withEnv(["FILENAME=${filename}", "CONTENT_TYPE=${contentType}",]) {
        sh '''
        # Don't output sensitive information
        set +x

        # Synchronize the File Share content
        azcopy copy \
          --skip-version-check \
          --put-md5 `# File length us used by default which can lead to errors for tiny text files` \
          --content-type="${CONTENT_TYPE}" \
          "${FILENAME}" "${FILESHARE_SIGNED_URL}"
        '''
      }
    }
  }
}
