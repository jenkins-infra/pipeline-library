#!/usr/bin/env groovy

/**
 *
 */
void call(String goals       = 'clean install',
          String options     = '-B -U -e -Dmaven.test.failure.ignore=true',
          String version     = '3.3.9',
          String jdk         = 'jdk-7',
          String testReports = 'target/surefire-reports/**/*.xml',
          String artifacts   = 'target/**/*.jar'
) {

    node('docker') {
        stage 'Checkout'
        /* make sure we have our source tree on this node */
        checkout scm

        stage 'Build'
        /* invoke maven inside of the official container */
        docker.image("maven:${version}-${jdk}").inside {
            sh "mvn ${goals} ${options}"
        }

        stage 'Archive'
        junit testReports
        archiveArtifacts artifacts: artifacts,
                       fingerprint: true
    }
}
