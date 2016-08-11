#!/usr/bin/env groovy

/**
 *
 */
void call(String goals       = 'clean install',
          String options     = '-B -U -e -Dmaven.test.failure.ignore=true',
          String jdk         = 'jdk-7',
          String testReports = 'target/surefire-reports/**/*.xml',
          String artifacts   = 'target/**/*.jar'
) {

    node('docker') {
        stage 'Checkout'
        checkout scm

        stage 'Build'
        docker.image("maven:3.3.9-${jdk}").inside {
            sh "mvn ${goals} ${options}"
        }

        stage 'Archive'
        junit testReports
        archiveArtifacts artifacts: artifacts,
                       fingerprint: true
    }
}
