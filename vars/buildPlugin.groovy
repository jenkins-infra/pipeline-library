#!/usr/bin/env groovy

/**
 * Simple wrapper step for building a plugin
 */
def call(Map options = [:]) {
    Map defaults = [
        jdkVersions : [7, 8],
        repo       : null,
        failFast   : true,
        platforms  : ['linux', 'windows'],
    ]
    options = defaults << options
    return buildPlugin(options.jdkVersions, options.failFast, options.repo, options.platforms)
}

def buildPlugin(List<Integer> jdkVersions,
                Boolean failFast,
                String repo,
                List<String> platforms
) {
    Map tasks = [:]

    for (int i = 0; i < platforms.size(); ++i) {
        for (int j = 0; j < jdkVersions.size(); ++j) {
            String label = platforms[i]
            String jdk = jdkVersions[j]
            String stageIdentifier = "${label}-${jdk}"

            tasks["${label}-${jdk}"] = {
                node(label) {
                    stage("Checkout (${stageIdentifier})") {
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

                    stage("Build (${stageIdentifier})") {
                        List<String> mavenOptions = [
                            '--batch-mode',
                            '--errors',
                            '--update-snapshots',
                            '-Dmaven.test.failure.ignore=true',
                            "-DskipAfterFailureCount=${failFast}",
                        ]
                        String mavenCommand = "mvn ${mavenOptions.join(' ')} clean install"
                        String jdkTool = "jdk${jdk}"

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

                    stage("Archive (${stageIdentifier})") {
                        timestamps {
                            junit '**/target/surefire-reports/**/*.xml'
                            archiveArtifacts artifacts: '**/target/*.hpi,**/target/*.jpi',
                                        fingerprint: true
                        }
                    }
                }
            }
        }
    }

    /* If we cannot complete in 60 minutes, we should fail the build. Compute
     * isn't free!
     */
    timeout(60) {
        return parallel(tasks)
    }
}
