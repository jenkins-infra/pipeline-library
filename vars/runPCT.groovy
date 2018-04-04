#!/usr/bin/env groovy

/**
 * Wrapper for running the PCT see README and runPCT.txt for full documentation
 */

def call(Map params = [:]) {
    def pctUrl = params.get('pctUrl', 'docker://jenkins/pct')
    def pctRevision = params.get('pctRevision', 'master')
    def metadataFile = params.get('metadataFile', 'essentials.yml')
    def jenkins = params.get('jenkins', 'latest')

    def defaultCategory = "org.jenkinsci.test.acceptance.junit.SmokeTest"
    def metadata
    def pctContainerImage
    def isPublishedImage
    def isLocalPCT

    def pctSourcesFolder = "pctSources"

    def skipExecution = false

    def localPluginsStashName = env.RUN_PCT_LOCAL_PLUGIN_SOURCES_STASH_NAME ?: "localPlugins"

    ensureInNode(env, env.RUN_PCT_SOURCES_AND_VALIDATION_NODE ?: "linux", {

        if (!fileExists(metadataFile)) {
            echo "Skipping PCT execution because the metadata file does not exist. Current value is ${metadataFile}."
            skipExecution = true
            return
        }

        stage("Getting PCT image and Jenkins war") {
            // TODO validate plugin list exists
            // Start validation
            metadata = readYaml(file: metadataFile)?.pct
            if (metadata == null) {
                echo "Skipping PCT execution because the metadata file does not contain a pct section"
                skipExecution = true
                return
            }
            if (metadata == 'default') {
                echo "Using default configuration for PCT"
                metadata = [:]
            }
            // Allow to override pctUrl and pctRevision from metadata file
            pctUrl = metadata.pctUrl ?: pctUrl
            pctRevision = metadata.pctRevision ?: pctRevision
            isPublishedImage = pctUrl.startsWith("docker://")
            isLocalPCT = pctUrl.startsWith("file://")

            // Allow override of jenkins version from metadata file
            jenkins = metadata.jenkins ?: jenkins

            if (!(isPublishedImage || isLocalPCT)) {
                echo 'Checking connectivity to PCT sources…'
                sh "git ls-remote --exit-code -h ${pctUrl}"
            }
            // Validation ended

            // Jenkins war
            infra.stashJenkinsWar(jenkins)
            // PCT
            // Only validate PCT if not running a remote docker image
            if (!isPublishedImage) {
                if (isLocalPCT) { // Deal with already existing PCT sources
                    pctSourcesFolder = pctUrl - "file://"
                } else {
                    dir(pctSourcesFolder) {
                        checkout changelog: true, poll: false, scm: [$class           : 'GitSCM', branches: [[name: pctRevision]],
                                                                     userRemoteConfigs: [[url: pctUrl]]]
                    }
                }
                dir(pctSourcesFolder) {
                    // We may need to run things in parallel later, so avoid several checkouts
                    stash name: "pct"
                }
            }
        }
    })

    if (skipExecution) {
        return
    }

    ensureInNode(env, env.RUN_PCT_DOCKER_NODE ?: 'docker', {

        stage("Running PCT") {
            if (!isPublishedImage) {
                dir("pctSources") {
                    unstash "pct"
                    pctContainerImage = docker.build("jenkins/pct:${env.BUILD_NUMBER}")
                }
            } else {
                def tag = pctUrl - "docker://"
                pctContainerImage = docker.image(tag)
            }

            def plugins = metadata.plugins

            def localSnapshots = metadata.useLocalSnapshots != null ? metadata.useLocalSnapshots : true

            def testingBranches = [:]
            def containerArgsBase = "-v /var/run/docker.sock:/var/run/docker.sock -v jenkins.war:/pct/jenkins.war:ro -u root"
            for (def i = 0; i < plugins.size(); i++) {
                def plugin = plugins[i]
                testingBranches["PCT-${plugin}"] = {
                    unstash "jenkinsWar"
                    pctContainerImage.inside(containerArgsBase) {
                        def command = 'run-pct'
                        if (localSnapshots && localPluginsStashName) {
                            dir("localPlugins") {
                                unstash name: localPluginsStashName
                            }
                            sh "cp -R localPlugins/${plugin}/* /pct/plugin-src"
                        } else {
                            command = "ARTIFACT_ID=${plugin} run-pct"
                        }
                        sh command
                        sh "mkdir reports${plugin} && cp /pct/tmp/work/*/target/surefire-reports/*.xml reports${plugin}"
                        junit healthScaleFactor: 0.0, testResults: "reports${plugin}/*.xml"
                    }
                }
            }

            parallel testingBranches
        }

    })
}

/*
 Make sure the code block is run in a node with the specified nodeLabel as label or name, if already running in that
 it simply executes the code block, if not allocates the desired node and runs the code inside it
  */
private void ensureInNode(env, nodeLabel, body) {
    if (env.NODE_NAME != nodeLabel && (env.NODE_LABELS == null || !env.NODE_LABELS.contains(nodeLabel))) {
        node(nodeLabel) {
            body()
        }
    } else {
        body()
    }
}

