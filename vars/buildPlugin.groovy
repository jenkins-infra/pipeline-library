#!/usr/bin/env groovy

/**
 * Simple wrapper step for building a plugin
 */
def call(Map options = [:]) {
    Map defaults = [
        jdkVersion : '7',
        platforms  : ['linux', 'windows'],
    ]
    options = defaults << options
    return buildPlugin(options.jdkVersion, options.platforms)
}

def buildPlugin(String jdkVersion,
                List<String> platforms
) {
    Map tasks = [:]

    for (int i = 0; i < platforms.size(); ++i) {
        String label = platforms[i]

        tasks[label] = {
            node(label) {
                stage("Checkout (${label})") {
                    checkout scm
                }

                stage("Build (${label})") {
                    String mavenCommand = 'mvn -B -U -e -Dmaven.test.failure.ignore=true clean install',

                    if (isUnix()) {
                        sh mavenCommand
                    }
                    else {
                        bat mavenCommand
                    }
                }

                stage("Archive (${label})") {
                    junit '**/target/surefire-reports/**/*.xml'
                    archiveArtifacts artifacts: '**/target/*.hpi,**/target/*.jpi',
                                fingerprint: true
                }
            }
        }
    }

    return parallel(tasks)
}
