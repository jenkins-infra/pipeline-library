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
        docker.image('azuresdk/azure-cli-python:0.1.5').inside {
            for(int i = 0; i < files.size(); ++i) {
                String filename = files[i]
                withEnv(['HOME=/tmp']) {
                    String uploadFlags = ''
                    if (filename.matches(/.*\.html/)) {
                        uploadFlags = '--content-type="text/html"'
                    }
                    sh "az storage blob upload --account-name=prodjenkinsreports --container=reports --timeout=${timeout} --file=${filename} --name=${filename} ${uploadFlags}"
                }
            }
        }
    }

}
