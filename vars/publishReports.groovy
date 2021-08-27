#!/usr/bin/env groovy

/**
 * Wrapper for publishing reports to the reports host
 *
 * See https://issues.jenkins-ci.org/browse/INFRA-947 for more
 */
def call(List<String> files, Map params = [:]) {
    def timeout = params.get('timeout') ?: '60'

    if (!infra.isTrusted()) {
        error 'Can only call publishReports from within the trusted.ci environment'
    }

    withCredentials([
        string(credentialsId: 'azure-reports-access-key', variable: 'AZURE_STORAGE_KEY'),
    ]) {
        docker.image('mcr.microsoft.com/azure-cli:2.0.41').inside {
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
                    // Blob container can be removed once files are uploaded on the azure file storage
                    sh "az storage blob upload --account-name=prodjenkinsreports --container=reports --timeout=${timeout} --file=${filename} --name=${filename} ${uploadFlags}"

                    // `az storage file upload` doesn't support file uploaded in a remote directory that doesn't exist but upload-batch yes. Unfortunatly the cli syntax is a bit different and require filename and directory name to be set differently.

                    def directory = filename.split("/")
                    def basename = directory[directory.size() - 1]
                    def dirname = Arrays.copyOfRange(directory, 0, directory.size()-1 ).join("/")

                    sh "az storage file upload-batch \
                      --account-name prodjenkinsreports \
                      --destination reports \
                      --source ${dirname ?: '.'} \
                      --destination-path ${dirname ?: '/'} \
                      --pattern ${ basename ?: '*' } \
                      ${uploadFlags}"
                }
            }
        }
    }

}
