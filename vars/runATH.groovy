#!/usr/bin/env groovy

/**
 * Wrapper for running the ATH see README and runATH.txt for full documentation
 */

def call(Map params = [:]) {
    def athUrl = params.get('athUrl', 'https://github.com/jenkinsci/acceptance-test-harness.git')
    def athRevision = params.get('athRevision', 'master')
    def metadataFile = params.get('metadataFile', 'essentials.yml')
    def jenkins = params.get('jenkins', 'latest')

    def mirror = "http://mirrors.jenkins.io/"
    def defaultCategory = "org.jenkinsci.test.acceptance.junit.SmokeTest"
    def jenkinsURl = jenkins
    def metadata
    def athContainerImage
    def isLocalATH
    def isVersionNumber

    def athSourcesFolder = "athSources"

    def supportedBrowsers = ["firefox"]

    def skipExecution = false

    def localPluginsStashName = env.RUN_ATH_LOCAL_PLUGINS_STASH_NAME ?: "localPlugins"

    ensureInNode(env, env.RUN_ATH_SOURCES_AND_VALIDATION_NODE ?: "linux", {
        List<String> env = [
                "JAVA_HOME=${tool 'jdk8'}",
                'PATH+JAVA=${JAVA_HOME}/bin',
                "PATH+MAVEN=${tool 'mvn'}/bin"

        ]

        if (!fileExists(metadataFile)) {
            echo "Skipping ATH execution because the metadata file does not exist. Current value is ${metadataFile}."
            skipExecution = true
            return
        }

        stage("Getting ATH sources and Jenkins war") {
            // Start validation
            metadata = readYaml(file: metadataFile)?.ath
            if (metadata == null) {
                error "The provided metadata file seems invalid as it does not contain a valid ath section"
            }
            if (metadata == 'default') {
                echo "Using default configuration for ATH"
                metadata = [:]
            } else if (metadata.browsers == null) {
                echo "The provided metadata file does not include the browsers property, using firefox as default"
            }
            // Allow to override athUrl and athRevision from metadata file
            athUrl = metadata.athUrl ?: athUrl
            isLocalATH = athUrl.startsWith("file://")
            athRevision = metadata.athRevision ?: athRevision

            // Allow override of jenkins version from metadata file
            jenkins = metadata.jenkins ?: jenkins
            isVersionNumber = (jenkins =~ /^\d+([.]\d+)*$/).matches()

            if (!isLocalATH) {
                echo 'Checking connectivity to ATH sources…'
                sh "git ls-remote --exit-code -h ${athUrl}"
            }
            if (jenkins == "latest") {
                jenkinsURl = mirror + "war/latest/jenkins.war"
            } else if (jenkins == "latest-rc") {
                jenkinsURl = mirror + "/war-rc/latest/jenkins.war"
            } else if (jenkins == "lts") {
                jenkinsURl = mirror + "war-stable/latest/jenkins.war"
            } else if (jenkins == "lts-rc") {
                jenkinsURl = mirror + "war-stable-rc/latest/jenkins.war"
            }

            if (!isVersionNumber) {
                echo 'Checking whether Jenkins WAR is available…'
                sh "curl -ILf ${jenkinsURl}"
            }
            // Validation ended

            // ATH
            if (isLocalATH) { // Deal with already existing ATH sources
                athSourcesFolder = athUrl - "file://"
            } else {
                dir(athSourcesFolder) {
                    checkout changelog: true, poll: false, scm: [$class           : 'GitSCM', branches: [[name: athRevision]],
                                                                 userRemoteConfigs: [[url: athUrl]]]
                }
            }
            dir(athSourcesFolder) {
                // We may need to run things in parallel later, so avoid several checkouts
                stash name: "athSources"
            }

            // Jenkins war
            if (isVersionNumber) {
                def downloadCommand = "mvn dependency:copy -Dartifact=org.jenkins-ci.main:jenkins-war:${jenkins}:war -DoutputDirectory=. -Dmdep.stripVersion=true"
                dir("deps") {
                    if (infra.isRunningOnJenkinsInfra()) {
                        def settingsXml = "${pwd tmp: true}/repo-settings.xml"
                        writeFile file: settingsXml, text: libraryResource('repo-settings.xml')
                        downloadCommand = downloadCommand + " -s ${settingsXml}"
                    }
                    withEnv(env) {
                        sh downloadCommand
                    }
                    sh "cp jenkins-war.war jenkins.war"
                    stash includes: 'jenkins.war', name: 'jenkinsWar'
                }
            } else {
                sh("curl -o jenkins.war -L ${jenkinsURl}")
                stash includes: '*.war', name: 'jenkinsWar'
            }

        }
    })

    ensureInNode(env, env.RUN_ATH_DOCKER_NODE ?: 'docker && highmem', {
        if (skipExecution) {
            return
        }
        stage("Running ATH") {
            dir("athSources") {
                unstash name: "athSources"
                def uid = sh(script: "id -u", returnStdout: true)
                def gid = sh(script: "id -g", returnStdout: true)
                athContainerImage = docker.build('jenkins/ath', "--build-arg=uid='$uid' --build-arg=gid='$gid' -f src/main/resources/ath-container/Dockerfile .")
            }

            def testsToRun = metadata.tests?.join(",")
            def categoriesToRun = metadata.categories?.join(",")
            def browsers = metadata.browsers ?: ["firefox"]
            def failFast = metadata.failFast ?: false
            def rerunCount = metadata.rerunFailingTestsCount ?: 0
            // Elvis fails in case useLocalSnapshots == false in metadata File
            def localSnapshots = metadata.useLocalSnapshots != null ? metadata.useLocalSnapshots : true

            if (testsToRun == null && categoriesToRun == null) {
                categoriesToRun = defaultCategory
            }

            def testingbranches = ["failFast": failFast]
            for (browser in browsers) {
                if (supportedBrowsers.contains(browser)) {

                    def currentBrowser = browser
                    def containerArgs = "-v /var/run/docker.sock:/var/run/docker.sock -e SHARED_DOCKER_SERVICE=true -e EXERCISEDPLUGINREPORTER=textfile -u ath-user"
                    def commandBase = "./run.sh ${currentBrowser} ./jenkins.war -Dmaven.test.failure.ignore=true -DforkCount=1 -B -Dsurefire.rerunFailingTestsCount=${rerunCount}"

                    if (testsToRun) {
                        testingbranches["ATH individual tests-${currentBrowser}"] = {
                            dir("test${currentBrowser}") {
                                def discriminator = "-Dtest=${testsToRun}"
                                test(discriminator, commandBase, localSnapshots, localPluginsStashName, containerArgs, athContainerImage)
                            }
                        }
                    }
                    if (categoriesToRun) {
                        testingbranches["ATH categories-${currentBrowser}"] = {
                            dir("categories${currentBrowser}") {
                                def discriminator = "-Dgroups=${categoriesToRun}"
                                test(discriminator, commandBase, localSnapshots, localPluginsStashName, containerArgs, athContainerImage)
                            }
                        }
                    }
                } else {
                    echo "${browser} is not yet supported"
                }
            }

            parallel testingbranches
        }

    })
}

private void test(discriminator, commandBase, localSnapshots, localPluginsStashName, containerArgs, athContainerImage) {
    unstashResources(localSnapshots, localPluginsStashName)
    athContainerImage.inside(containerArgs) {
        realtimeJUnit(testResults: 'target/surefire-reports/TEST-*.xml', testDataPublishers: [[$class: 'AttachmentPublisher']]) {
            sh 'eval "$(./vnc.sh)" && ' + prepareCommand(commandBase, discriminator, localSnapshots, localPluginsStashName)
        }
    }
}

private String prepareCommand(commandBase, discriminator, localSnapshots, localPluginsStashName ) {
    def command = commandBase + " ${discriminator}"
    if (localSnapshots && localPluginsStashName) {
        command = "LOCAL_JARS=${getLocalPluginsList()} " + command
    }
    command
}

private void unstashResources(localSnapshots, localPluginsStashName) {
    unstash name: "athSources"
    unstash name: "jenkinsWar"
    dir("localPlugins") {
        if (localSnapshots && localPluginsStashName) {
            unstash name: localPluginsStashName
        }
    }
}

private String getLocalPluginsList() {
    dir("localPlugins") {
       return sh(script : "ls -p -d -1 ${pwd()}/*.* | tr '\n' ':'| sed 's/.\$//'", returnStdout: true).trim()
    }
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