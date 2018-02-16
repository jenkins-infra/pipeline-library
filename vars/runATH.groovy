#!/usr/bin/env groovy

/**
 * Simple wrapper for running the ATH
 */

def call(Map params = [:]) {
    
    def String athUrl = params.get('athUrl', 'https://github.com/jenkinsci/acceptance-test-harness.git')
    def String athRevision = params.get('athRevision', 'master')
    def String metadataFile = params.get('metadataFile', 'essentials.yml')
    def String jenkins = params.get('jenkins','latest')
    def String[] platforms = params.get('platforms', ['linux'])

    def athSourcesFolder = "athSources"

    stage("Getting ATH sources (") {
        if (athUrl.startsWith("file://")) { // Deal with already existing ATH sources
            athSourcesFolder = athUrl - "file://"
        } else { // TODO validate this is a valid git url
            dir(athSourcesFolder) {
                checkout changelog: true, poll: false, scm: [$class           : 'GitSCM', branches: [[name: "${athRevision}"]],
                                                             userRemoteConfigs: [[url: "${athUrl}"]]]
            }
        }

        dir(athSourcesFolder) {
            stash name: "athSources"
        }
    }

    stage("Getting jenkins war") {

    }

    stage("Running ATH") {
        dir("work") {
            unstash name: "athSources"
            def uid = sh(script: "id -u", returnStdout: true)
            def gid = sh(script: "id -g", returnStdout: true)
            def image = docker.build('jenkins/ath', "--build-arg=uid='$uid' --build-arg=gid='$gid' -f src/main/resources/ath-container/Dockerfile .")
            image.inside('-v /var/run/docker.sock:/var/run/docker.sock -e LOCAL_SNAPSHOTS=true -e SHARED_DOCKER_SERVICE=true -u ath-user') {
                realtimeJUnit(testResults: 'target/surefire-reports/TEST-*.xml', testDataPublishers: [[$class: 'AttachmentPublisher']]) {
                    sh '''
                                eval $(./vnc.sh)
                                ./run.sh firefox latest -Dmaven.test.failure.ignore=true -DforkCount=1 -Dgroups=org.jenkinsci.test.acceptance.junit.SmokeTest -B
                            '''
                }

            }
        }
    }

}