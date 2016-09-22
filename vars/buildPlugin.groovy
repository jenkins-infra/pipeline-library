#!/usr/bin/env groovy

/**
 * Simple wrapper step for building a plugin
 */
def call(Map options = [:]) {
    Map defaults = [
        jdkVersion : '7',
        repo       : null,
        failFast   : true,
        platforms  : ['linux', 'windows'],
    ]
    options = defaults << options
    return buildPlugin(options.jdkVersion, options.failFast, options.repo, options.platforms)
}

def buildPlugin(String jdkVersion,
                Boolean failFast,
                String repo,
                List<String> platforms
) {
    Map tasks = [:]

    for (int i = 0; i < platforms.size(); ++i) {
        String label = platforms[i]

        tasks[label] = {
            node(label) {
                stage("Checkout (${label})") {
                    if (env.BRANCH_NAME) {
                        timestamps {
                            checkout scm
                        }
                    }
                    else if ((env.BRANCH_NAME == null) && (repo)) {
                        timestamps {
                            git repo
                        }
                    }
                    else {
                        error 'buildPlugin must be used as part of a Multibranch Pipeline *or* a `repo` argument must be provided'
                    }
                }

                stage("Build (${label})") {
                    List<String> mavenOptions = [
                        '--batch-mode',
                        '--errors',
                        '--update-snapshots',
                        '-Dmaven.test.failure.ignore=true',
                        "-DskipAfterFailureCount=${failFast}",
                    ]
                    String mavenCommand = "mvn ${mavenOptions.join(' ')} clean install"
                    String jdkTool = "jdk${jdkVersion}"

                    withEnv([
                        "JAVA_HOME=${tool jdkTool}",
                        "PATH+MAVEN=${tool 'mvn'}/bin",
                        'PATH+JAVA=${JAVA_HOME}/bin',
                    ]) {
                        if (isUnix()) {
                            timestamps {
                                sh mavenCommand
                            }
                        }
                        else {
                            timestamps {
                                bat mavenCommand
                            }
                        }
                    }
                }

                stage("Archive (${label})") {
                    timestamps {
                        junit '**/target/surefire-reports/**/*.xml'
                        archiveArtifacts artifacts: '**/target/*.hpi,**/target/*.jpi',
                                       fingerprint: true
                    }
                }
            }
        }
    }

    return parallel(tasks)
}
