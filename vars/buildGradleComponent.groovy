#!/usr/bin/env groovy

/**
 * Builds a component using Gradle.
 * This build has less features than Maven-based builds.
 *
 * @param stageIdentifier Stage identifier
 * @param label Node label to be used, {@code linux} by default
 * @param jdk JDK version to be used, {@code 8} by default
 * @param jenkinsVersion Version of Jenkins to be used. {@code null} if the default version in pom.xml should be used
 * @param repo Repository to be used for Git checkout. Use {@code null} for Multi-Branch
 * @param failFast Fail the build if one of the branches fails
 */
def call(String stageIdentifier, String label = "linux", String jdk = 8, String jenkinsVersion = null,
         String repo = null, boolean failFast = true) {

    node(label) {
        timeout(60) {
            String testReports
            String artifacts

            stage("Checkout (${stageIdentifier})") {
                commons.checkout(repo)
                testReports = '**/build/test-results/**/*.xml'
                artifacts = '**/build/libs/*.hpi,**/build/libs/*.jpi'
            }

            stage("Build (${stageIdentifier})") {
                List<String> gradleOptions = [
                    '--no-daemon',
                    'cleanTest',
                    'build',
                ]
                String command = "gradlew ${gradleOptions.join(' ')}"
                if (isUnix()) {
                    command = "./" + command
                }

                commons.runWithJava(command, jdk)
            }

            stage("Archive (${stageIdentifier})") {
                junit testReports
                if (failFast && currentBuild.result == 'UNSTABLE') {
                    error 'There were test failures; halting early'
                }
                archiveArtifacts artifacts: artifacts, fingerprint: true
            }
        }
    }

    return;
}
