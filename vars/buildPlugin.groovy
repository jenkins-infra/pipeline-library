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
                        boolean isMaven

                        stage("Checkout (${stageIdentifier})") {
                            if (env.BRANCH_NAME) {
                                checkout scm
                            }
                            else if ((env.BRANCH_NAME == null) && (repo)) {
                                git repo
                            }
                            else {
                                error 'buildPlugin must be used as part of a Multibranch Pipeline *or* a `repo` argument must be provided'
                            }

                            isMaven = fileExists('pom.xml')
                        }

                        stage("Build (${stageIdentifier})") {
                            String jdkTool = "jdk${jdk}"
                            List<String> env = [
                                    "JAVA_HOME=${tool jdkTool}",
                                    'PATH+JAVA=${JAVA_HOME}/bin',
                            ]
                            String command
                            if (isMaven) {
                                List<String> mavenOptions = [
                                        '--batch-mode',
                                        '--errors',
                                        '--update-snapshots',
                                        '-Dmaven.test.failure.ignore',
                                ]
                                if (jdk.toInteger() > 7) {
                                    /* Azure mirror only works for sufficiently new versions of the JDK due to Letsencrypt cert */
                                    def settingsXml = "${pwd tmp: true}/settings-azure.xml"
                                    writeFile file: settingsXml, text: libraryResource('settings-azure.xml')
                                    mavenOptions += "-s '$settingsXml'"
                                }
                                if (jenkinsVersion) {
                                    mavenOptions += "-Djenkins.version=${jenkinsVersion}"
                                }
                                command = "mvn ${mavenOptions.join(' ')} clean install"
                                env << "PATH+MAVEN=${tool 'mvn'}/bin"
                            } else {
                                List<String> gradleOptions = [
                                        '--no-daemon',
                                        'cleanTest',
                                        'build',
                                ]
                                command = "gradlew ${gradleOptions.join(' ')}"
                                if (isUnix()) {
                                    command = "./" + command
                                }
                            }

                            withEnv(env) {
                                if (isUnix()) { // TODO JENKINS-44231 candidate for simplification
                                    sh command
                                }
                                else {
                                    bat command
                                }
                            }
                        }

                        stage("Archive (${stageIdentifier})") {
                            String testReports
                            String artifacts
                            if (isMaven) {
                                testReports = '**/target/surefire-reports/**/*.xml'
                                artifacts = '**/target/*.hpi,**/target/*.jpi'
                            } else {
                                testReports = '**/build/test-results/**/*.xml'
                                artifacts = '**/build/libs/*.hpi,**/build/libs/*.jpi'
                            }

                            junit testReports // TODO do this in a finally-block so we capture all test results even if one branch aborts early
                            if (failFast && currentBuild.result == 'UNSTABLE') {
                                error 'There were test failures; halting early'
                            }
                            archiveArtifacts artifacts: artifacts, fingerprint: true
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
        timestamps {
            return parallel(tasks)
        }
    }
}
