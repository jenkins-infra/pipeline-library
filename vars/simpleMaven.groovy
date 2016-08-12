#!/usr/bin/env groovy

/**
 *
 */
void call(Map opts = [:], String goals) {
    Map defaults = [
        options     : '-B -U -e -Dmaven.test.failure.ignore=true',
        version     : '3.3.9',
        jdk         : 'jdk-7',
        testReports : 'target/surefire-reports/**/*.xml',
        artifacts   : 'target/**/*.jar',
        defineStages: true,
    ]
    Map options = defaults << opts

    node('docker') {
        if (options.defineStages) {
            stage "Checkout ${options.jdk}"
        }
        /* make sure we have our source tree on this node */
        checkout scm

        if (options.defineStages) {
            stage "Build ${options.jdk}"
        }
        /* https://github.com/carlossg/docker-maven#running-as-non-root */
        def runArgs = '-v $HOME/.m2:/var/maven/.m2'
        /* Make sure our ~/.m2 directory is there, if we let Docker create it,
         * it will be chowned to root!
         */
        sh 'mkdir -p $HOME/.m2'

        /* invoke maven inside of the official container */
        docker.image("maven:${options.version}-${options.jdk}").inside(runArgs) {
            timestamps {
                sh "mvn ${goals} ${options.options} -Duser.home=/var/maven"
            }
        }

        if (options.defineStages) {
            stage "Archive ${options.jdk}"
        }
        junit options.testReports
        archiveArtifacts artifacts: options.artifacts,
                       fingerprint: true
    }
}
