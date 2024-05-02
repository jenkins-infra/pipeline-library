#!/usr/bin/env groovy

/**
 * Wrapper for publishing reports to the reports host
 *
 * @param timeout: timout in minutes for the blob storage upload (default: 60)
 * @param failIfEmpty: set to true to fail if the file to upload isn't empty (default: false)
 *
 * See https://issues.jenkins-ci.org/browse/INFRA-947 for more
 */
def call(List<String> files, Map params = [:]) {
  def timeout = params.get('timeout') ?: '60'
  def failIfEmpty = params.get('failIfEmpty') ?: false

  if (!infra.isTrusted() && !infra.isInfra()) {
    error 'Can only call publishReports from within the trusted.ci environment'
  }

  withCredentials([string(credentialsId: 'azure-reports-access-key', variable: 'AZURE_STORAGE_KEY'),]) {
    // Sanity Check to check that `az` is installed, in the PATH, and in a decent version
    sh 'az version'

    for(int i = 0; i < files.size(); ++i) {
      String filename = files[i]
      withEnv(['HOME=/tmp']) {
        String uploadFlags = ''
        switch (filename) {
          case ~/(?i).*\.html/:
            uploadFlags = '--content-type="text/html"'
            break
          case ~/(?i).*\.css/:
            uploadFlags = '--content-type="text/css"'
            break
          case ~/(?i).*\.json/:
            uploadFlags = '--content-type="application/json"'
            break
          case ~/(?i).*\.js/:
            uploadFlags = '--content-type="application/javascript"'
            break
          case ~/(?i).*\.gif/:
            uploadFlags = '--content-type="image/gif"'
            break
          case ~/(?i).*\.png/:
            uploadFlags = '--content-type="image/png"'
            break
        }
        def directory = filename.split("/")
        def basename = directory[directory.size() - 1]
        def dirname = Arrays.copyOfRange(directory, 0, directory.size()-1 ).join("/")

        withEnv([
          "TIMEOUT=${timeout}",
          "FILENAME=${filename}",
          "UPLOADFLAGS=${uploadFlags}",
          "SOURCE_DIRNAME=${dirname ?: '.'}",
          "DESTINATION_PATH=${dirname ?: '/'}",
          "PATTERN=${ basename ?: '*' }",
        ]) {
          sh '''
          # If the "failIsEmpty" flag is active and the file is empty, fail the pipeline
          lineCount=$(wc -l "${filename}" | awk '{print $1}')
          if [[ "${failIsEmpty}" != false && "${lineCount}" -eq 0 ]]; then
            echo "ERROR: ${filename} is empty."
            exit 1
          else
            # Blob container can be removed once files are uploaded on the azure file storage
            az storage blob upload --account-name=prodjenkinsreports --container=reports --timeout="${TIMEOUT}" --file="${FILENAME}" --name="${FILENAME}" "${UPLOADFLAGS}" --overwrite

            # `az storage file upload` doesn't support file uploaded in a remote directory that doesn't exist but upload-batch yes. Unfortunately the cli syntax is a bit different and requires filename and directory name to be set differently.
            az storage file upload-batch --account-name prodjenkinsreports --destination reports --source "${SOURCE_DIRNAME}" --destination-path "${DESTINATION_PATH}" --pattern "${PATTERN}" "${UPLOADFLAGS}"
          fi
          '''
        }
      }
    }
  }
}
