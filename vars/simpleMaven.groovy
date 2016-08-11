#!/usr/bin/env groovy

/**
 *
 */
void call(String goals       = 'clean install',
          String options     = '-B -U -e -Dmaven.test.failure.ignore=true',
          String jdk         = '8-jdk',
          String testReports = 'target/surefire-reports/**/*.xml',
          String artifacts   = 'target/**/*.jar'
) {

    node('docker') {
        stage 'Checkout'
        checkout scm

        stage 'Build'
        docker.image("java:${jdk}").inside {
            withEnv([
                "PATH+MAVEN=${tool 'mvn'}/bin",
            ]) {
                sh "mvn ${goals} ${options}"
            }
        }

        stage 'Archive'
        junit testReports
        archiveArtifacts artifacts: artifacts,
                       fingerprint: true
    }
}
