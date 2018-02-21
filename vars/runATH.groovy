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
    def isVersionNumber = (jenkins =~ /^(\d+\.)?(\d+\.)?(\*|\d+)$/).matches()
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
                error "The provided metadata file seems invalid as it does not contain the ath section"
            }
            if (metadata.browsers == null) {
                error "The provided metadata file seems invalid as it does not include the browser property"
            }
        }
        if (!isLocalATH) {
            def canConnectToGitRepo = sh(script: "git ls-remote --exit-code -h ${athUrl}", returnStatus: true) == 0
            if (!canConnectToGitRepo) {
                error "Is not possible to connect to the given athUrl, please review it. Current value for athUrl is ${athUrl}"
            }
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

        def canFindJenkinsWar = sh(script: "curl --output /dev/null --silent --fail -r 0-0 -L '${jenkinsURl}'", returnStatus: true) == 0
        if (!canFindJenkinsWar) {
            error "Is not possible to find the given jenkins war file, please review it. Current value for jenkins is ${jenkins}"
        }
        // Validation ended

        // ATH
        if (isLocalATH) { // Deal with already existing ATH sources
            athSourcesFolder = athUrl - "file://"
        } else {
            dir(athSourcesFolder) {
                checkout changelog: true, poll: false, scm: [$class           : 'GitSCM', branches: [[name: "${athRevision}"]],
                                                             userRemoteConfigs: [[url: "${athUrl}"]]]
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
        def browsers = metadata.browsers
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
                def containerArgs = "-v /var/run/docker.sock:/var/run/docker.sock -e LOCAL_SNAPSHOTS=${localSnapshots} -e SHARED_DOCKER_SERVICE=true -u ath-user"
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