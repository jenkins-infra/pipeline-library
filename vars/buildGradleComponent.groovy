#!/usr/bin/env groovy

/**
 * Builds a component, which implements Jenkins Plugin POM.
 * It may be either Jenkins plugin or module, depending on the packaging.
 */
def call(String stageIdentifier, String label = "linux", String jdk = 8, String repo = null, boolean failFast = true) {
    node(label) {
        timeout(60) {
            String testReports
            String artifacts

            stage("Checkout (${stageIdentifier})") {
                commonSteps.checkout(repo)
            }

            stage("Analyze project") {
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

                commonSteps.runWithJava(command, jdk)
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
