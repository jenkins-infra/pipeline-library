#!/usr/bin/env groovy

/**
 *
 */
void call(String goals       = 'clean install',
          String options     = '-B -U -e -Dmaven.test.failure.ignore=true',
          String jdk         = 'jdk7',
          String testReports = 'target/surefire-reports/**/*.xml',
          String artifacts   = 'target/**/*.jar'
) {

    node {
        stage 'Checkout'
        checkout scm

        stage 'Build'
        withEnv([
            "JAVA_HOME=${tool jdk}",
            "PATH+MAVEN=${tool 'mvn'}/bin",
            "PATH+JAVA=${env.JAVA_HOME}/bin",
        ]) {
            sh "mvn ${goals} ${options}"
        }

        stage 'Archive'
        junit testReports
        archiveArtifacts artifacts: artifacts,
                       fingerprint: true
    }
}
