#!/usr/bin/env groovy

/**
 * Build a component defined by Jenkins parent POM.
 * @param params Parameters to be passed
 */
def call(Map params = [:]) {
    // These platforms correspond to labels in ci.jenkins.io, see:
    //  https://github.com/jenkins-infra/documentation/blob/master/ci.adoc
    def platforms = params.containsKey('platforms') ? params.platforms : ['linux', 'windows']
    def jdkVersions = params.containsKey('jdkVersions') ? params.jdkVersions : [8]
    int timeoutMin = params.containsKey('timeoutMin') ? Integer.parseInt(params.timeoutMin) : [8]

    Map branches = [:]
    for (int i = 0; i < platforms.size(); ++i) {
        String label = platforms[i]
        for (int j = 0; j < jdkVersions.size(); ++j) {
            String jdk = jdkVersions[j]
            String stageIdentifier = "${label}${jdkVersions.size() > 1 ? '-' + jdk : ''}"
            boolean first = i == 0 && j == 0
            branches[label] = {
                buildSingle(stageIdentifier, label, jdk, first, timeoutMin)
            }
        }
    }

    /* Execute our platforms in parallel */
    parallel(branches)
}

/**
 * Builds a single configuration of a component.
 * @param stageIdentifier Stage identifier for visualization
 * @param nodeLabel Node label
 * @param jdk JDK to be used, {@code 8} by default
 * @param archive If {@code true}, arhive results.
 *                {@code false} by default
 * @param timeoutMin Timeout for the Maven run (in minutes)
 * @return nothing
 */
def buildSingle(String stageIdentifier, String nodeLabel, String jdk = 8, boolean archive = false, int timeoutMin = 60) {
    node(label) {
        timeout(timeoutMin) {
            timestamps {
                stage("Checkout (${stageIdentifier})") {
                    infra.checkout(repo)
                    isMaven = fileExists('pom.xml')
                }

                stage("Build (${stageIdentifier})") {
                    infra.runMaven(["--batch-mode", "clean", "install", "-Dmaven.test.failure.ignore=true"], jdk)
                }

                stage("Archive (${stageIdentifier})") {
                    /* Archive the test results */
                    junit '**/target/surefire-reports/TEST-*.xml'

                    if (archive) {
                        archiveArtifacts artifacts: '**/target/**/*.jar'
                        findbugs pattern: '**/target/findbugsXml.xml'
                    }
                }
            }
        }
    }
}