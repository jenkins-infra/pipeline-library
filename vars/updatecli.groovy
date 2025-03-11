/**
 Note: this shared pipeline is tailored to Jenkins infrastructure usage and thus sets some default values which might not be desirable for yours.
 **/

def call(userConfig = [:]) {
    def defaultConfig = [
        action: 'diff',                         // Updatecli subcommand to execute
        config: './updatecli/updatecli.d',      // Config manifest (file or directory) for updatecli
        values: './updatecli/values.yaml',      // Values file used by updatecli
        updatecliAgentLabel: 'jnlp-linux-arm64', // Label to select the Jenkins node (agent)
        cronTriggerExpression: '',               // Enables cron trigger if specified
        credentialsId: 'github-app-updatecli-on-jenkins-infra', // GitHub credentials
        version: ''                               // Custom updatecli version (e.g. '0.92.0' or '0.86.0-rc.1')
    ]

    // TODO: use isInfra() to set a default githubApp credentials id for infra & for ci
    // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html  final Map
    finalConfig = defaultConfig << userConfig
    final String customUpdatecliPath = "/tmp/custom_updatecli" // Factorized custom path

    // Set cron trigger if requested
    if (finalConfig.cronTriggerExpression) {
        properties([pipelineTriggers([cron(finalConfig.cronTriggerExpression)])])
    }

    node(finalConfig.updatecliAgentLabel) {
        final String updatecliRunStage = "Run updatecli: ${finalConfig.action}"
        boolean runUpdatecli = true

        stage("Check if ${finalConfig.config} folder exists: ${finalConfig.action}") {
            checkout scm
            if (!fileExists(finalConfig.config)) {
                echo "WARNING: no ${finalConfig.config} folder."
                runUpdatecli = false
                org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional(updatecliRunStage)
            }
        }

        stage(updatecliRunStage) {
            withEnv(["PATH+CUSTOM=$customUpdatecliPath"]) { // Wrap entire execution inside PATH update
                if (runUpdatecli) {
                    // If a custom version is provided, download and install that version of updatecli
                    if (finalConfig.version) {
                        stage("Download updatecli version ${finalConfig.version}") {
                            sh 'echo $PATH'
                            withEnv(["UPDATECLI_VERSION=${finalConfig.version}", "CUSTOM_UPDATECLI_PATH=${customUpdatecliPath}"]) { // Only custom installation vars
                                sh '''
                                    versionTag="v${UPDATECLI_VERSION}"
                                    cpu="$(uname -m)"
                                    # Normalize CPU architecture for Updatecli release filenames
                                    if [ "$cpu" = "aarch64" ] || [ "$cpu" = "arm64" ]; then
                                        cpu="arm64"
                                    fi
                                    # Construct the tar file name based on the CPU architecture.
                                    tarFileName="updatecli_Linux_${cpu}.tar.gz"
                                    downloadUrl="https://github.com/updatecli/updatecli/releases/download/${versionTag}/${tarFileName}"
                                    echo "Downloading updatecli version ${UPDATECLI_VERSION} from ${downloadUrl}"
                                    curl --silent --location --output ${tarFileName} ${downloadUrl} || { echo "Download failed"; exit 1; }
                                    echo "customUpdatecliPath is: ${PATH}"
                                    # Create destination directory for extraction.
                                    mkdir -p ${CUSTOM_UPDATECLI_PATH}
                                    # Extract the updatecli binary from the tar file.
                                    tar --extract --gzip --file="${tarFileName}" --directory=${CUSTOM_UPDATECLI_PATH} updatecli
                                    # Remove the tar file leaving only the executable binary.
                                    rm -f ${tarFileName}
                                    # Debugging: Verify PATH and the resolved updatecli binary
                                    echo "Updated PATH: $PATH"
                                    echo "Resolved updatecli path: $(which updatecli)"
                                    echo "$(ls -l /tmp/custom_updatecli/updatecli)"
                                '''
                            }
                        }
                    }

                    // Build the updatecli command
                    def updatecliCommand = ""
                    updatecliCommand = "updatecli ${finalConfig.action}"
                    updatecliCommand += finalConfig.config ? " --config ${finalConfig.config}" : ""
                    updatecliCommand += finalConfig.values ? " --values ${finalConfig.values}" : ""
                    withCredentials([
                        usernamePassword(
                            credentialsId: finalConfig.credentialsId,
                            usernameVariable: 'USERNAME_VALUE', // Setting this variable is mandatory, even if of not used when the credentials is a githubApp one
                            passwordVariable: 'UPDATECLI_GITHUB_TOKEN'
                        )
                    ]) {
                        // check the existing updatecli version.
                        sh 'updatecli version'
                        sh updatecliCommand
                    }
                } // if (runUpdatecli)
            } // withEnv (["PATH+CUSTOM=$customUpdatecliPath"])
        } // stage(updatecliRunStage)
    } // node
}
