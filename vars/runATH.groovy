#!/usr/bin/env groovy

/**
 * Simple wrapper for running the ATH
 */

def call(Map params = [:]) {

    def String athUrl = params.get('athUrl', 'https://github.com/jenkinsci/acceptance-test-harness.git')
    def String athRevision = params.get('athRevision', 'master')
    def String metadataFile = params.get('metadataFile', 'essentials.yml')
    def String jenkins = params.get('jenkins', 'latest')

    def isLocalATH = athUrl.startsWith("file://")
    def isVersionNumber = (jenkins =~ /^\d+([.]\d+)*$/).matches()
    // Workaround for https://issues.jenkins-ci.org/browse/JENKINS-27092
    def shouldStop = false
    // End of workaround

    def mirror = "http://mirrors.jenkins.io/"
    def defaultCategory = "org.jenkinsci.test.acceptance.junit.SmokeTest"
    def jenkinsURl = jenkins
    def metadata
    def athContainerImage


    def athSourcesFolder = "athSources"

    stage("Getting ATH sources and Jenkins war") {
        // Start validation
        if (!fileExists(metadataFile)) {
            echo "The metadata file does not exist. Current value is ${metadataFile}"
            shouldStop = true
            return
        } else {
            metadata = readYaml(file: metadataFile).ath
            if (metadata == null) {
                error "The provided metadata file seems invalid as it does not contain a valid ath section"
            }
            if (metadata == 'default') {
                echo "Using default configuration for ATH"
                metadata = [:]
            } else if (metadata.browsers == null) {
                echo "The provided metadata file does not include the browsers property, using firefox as default"
            }
        }
        if (!isLocalATH) {
            echo 'Checking connectivity to ATH sources…'
            sh "git ls-remote --exit-code -h ${athUrl}"
        }
        if (isVersionNumber) {
            jenkinsURl = mirror + "war/${jenkins}/jenkins.war"
        } else if (jenkins == "latest") {
            jenkinsURl = mirror + "war/latest/jenkins.war"
        } else if (jenkins == "latest-rc") {
            jenkinsURl = mirror + "/war-rc/latest/jenkins.war"
        } else if (jenkins == "lts") {
            jenkinsURl = mirror + "war-stable/latest/jenkins.war"
        } else if (jenkins == "lts-rc") {
            jenkinsURl = mirror + "war-stable-rc/latest/jenkins.war"
        }

        echo 'Checking whether Jenkins WAR is available…'
        sh "curl -ILf ${jenkinsURl}"
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
            def uid = sh(script: "id -u", returnStdout: true)
            def gid = sh(script: "id -g", returnStdout: true)
            athContainerImage = docker.build('jenkins/ath', "--build-arg=uid='$uid' --build-arg=gid='$gid' -f src/main/resources/ath-container/Dockerfile .")
            // We may need to run things in parallel later, so avoid several checkouts
            stash name: "athSources"
        }

        // Jenkins war
        sh("curl -o jenkins.war -L ${jenkinsURl}")
        stash includes: '*.war', name: 'jenkinsWar'

    }
    // Workaround for https://issues.jenkins-ci.org/browse/JENKINS-27092
    if (shouldStop) {
        return
    }
    // End of workaround

    stage("Running ATH") {
        def testsToRun = metadata.tests?.join(",")
        def categoriesToRun = metadata.categories?.join(",")
        def browsers = metadata.browsers ?: ["firefox"]
        def failFast = metadata.failFast ?: false
        def rerunCount = metadata.rerunFailingTestsCount ?: 0
        def localSnapshots = metadata.useLocalSnapshots ?: true

        if (testsToRun == null && categoriesToRun == null) {
            categoriesToRun = defaultCategory
        }

        def testingbranches = ["failFast": failFast]
        for (browser in browsers) {
            // TODO remove this if else block once other browser are supported by infra
            if (browser == "firefox") {

                def currentBrowser = browser
                def containerArgs = "-v /var/run/docker.sock:/var/run/docker.sock -v \$HOME/.m2/repository:/home/ath-user/.m2/repository -e LOCAL_SNAPSHOTS=${localSnapshots} -e SHARED_DOCKER_SERVICE=true -u ath-user"
                def commandBase = "./run.sh ${currentBrowser} jenkins.war -Dmaven.test.failure.ignore=true -DforkCount=1 -B -Dsurefire.rerunFailingTestsCount=${rerunCount}"

                if (testsToRun) {
                    testingbranches["ATH individual tests-${currentBrowser}"] = {
                        dir("test${currentBrowser}") {
                            unstash name: "athSources"
                            unstash name: "jenkinsWar"
                            def command = commandBase + " -Dtest=${testsToRun}"
                            athContainerImage.inside(containerArgs) {
                                realtimeJUnit(testResults: 'target/surefire-reports/TEST-*.xml', testDataPublishers: [[$class: 'AttachmentPublisher']]) {
                                    sh 'eval "$(./vnc.sh)" && ' + command
                                }

                            }
                        }
                    }
                }
                if (categoriesToRun) {
                    testingbranches["ATH categories-${currentBrowser}"] = {
                        dir("categories${currentBrowser}") {
                            unstash name: "athSources"
                            unstash name: "jenkinsWar"
                            def command = commandBase + " -Dgroups=${categoriesToRun}"
                            athContainerImage.inside(containerArgs) {
                                realtimeJUnit(testResults: 'target/surefire-reports/TEST-*.xml', testDataPublishers: [[$class: 'AttachmentPublisher']]) {
                                    sh 'eval "$(./vnc.sh)" && ' + command
                                }

                            }
                        }
                    }
                }
            } else {
                echo "${browser} is not yet supported"
            }
        }

        parallel testingbranches
    }

}