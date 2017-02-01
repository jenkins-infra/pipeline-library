#!/usr/bin/env groovy

/**
 * Simple wrapper step for building a plugin
 */
def call(Map params = [:]) {
    def platforms = params.containsKey('platforms') ? params.platforms : ['linux', 'windows']
    def jdkVersions = params.containsKey('jdkVersions') ? params.jdkVersions : [8]
    def jenkinsVersions = params.containsKey('jenkinsVersions') ? params.jenkinsVersions : [null]
    def repo = params.containsKey('repo') ? params.repo : null
    def failFast = params.containsKey('failFast') ? params.failFast : true
    Map tasks = [failFast: failFast]
    for (int i = 0; i < platforms.size(); ++i) {
        for (int j = 0; j < jdkVersions.size(); ++j) {
            for (int k = 0; k < jenkinsVersions.size(); ++k) {
                String label = platforms[i]
                String jdk = jdkVersions[j]
                String jenkinsVersion = jenkinsVersions[k]
                String stageIdentifier = "${label}-${jdk}${jenkinsVersion ? '-' + jenkinsVersion : ''}"

                tasks[stageIdentifier] = {
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
                            if (jenkinsVersion) {
                                mavenOptions += "-Djenkins.version=${jenkinsVersion}"
                            }
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
                                if (failFast && currentBuild.result == 'UNSTABLE') {
                                    error 'There were test failures; halting early'
                                }
                                archiveArtifacts artifacts: '**/target/*.hpi,**/target/*.jpi',
                                            fingerprint: true
                            }
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
