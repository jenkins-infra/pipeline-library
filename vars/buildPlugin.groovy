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
                String stageIdentifier = "${label}${jdkVersions.size() > 1 ? '-' + jdk : ''}${jenkinsVersion ? '-' + jenkinsVersion : ''}"
                boolean first = i == 0 && j == 0 && k == 0
                boolean runFindbugs = first && params?.findbugs?.run
                boolean runCheckstyle = first && params?.checkstyle?.run
                boolean archiveFindbugs = first && params?.findbugs?.archive
                boolean archiveCheckstyle = first && params?.checkstyle?.archive

                tasks[stageIdentifier] = {
                    node(label) {
                        timeout(60) {
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
                                if (jdk.toInteger() > 7 && infra.isRunningOnJenkinsInfra()) {
                                    /* Azure mirror only works for sufficiently new versions of the JDK due to Letsencrypt cert */
                                    def settingsXml = "${pwd tmp: true}/settings-azure.xml"
                                    writeFile file: settingsXml, text: libraryResource('settings-azure.xml')
                                    mavenOptions += "-s $settingsXml"
                                }
                                if (jenkinsVersion) {
                                    mavenOptions += "-Djenkins.version=${jenkinsVersion}"
                                }
                                if (params?.findbugs?.run || params?.findbugs?.archive) {
                                    mavenOptions += "-Dfindbugs.failOnError=false"
                                }
                                if (params?.checkstyle?.run || params?.checkstyle?.archive) {
                                    mavenOptions += "-Dcheckstyle.failOnViolation=false -Dcheckstyle.failsOnError=false"
                                }
                                mavenOptions += "clean install"
                                if (runFindbugs) {
                                    mavenOptions += "findbugs:findbugs"
                                }
                                if (runCheckstyle) {
                                    mavenOptions += "checkstyle:checkstyle"
                                }
                                command = "mvn ${mavenOptions.join(' ')}"
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
                            if (isMaven && archiveFindbugs) {
                                def fp = [pattern: params?.findbugs?.pattern ?: '**/target/findbugsXml.xml']
                                if (params?.findbugs?.unstableNewAll) {
                                    fp['unstableNewAll'] ="${params.findbugs.unstableNewAll}"
                                }
                                if (params?.findbugs?.unstableTotalAll) {
                                    fp['unstableTotalAll'] ="${params.findbugs.unstableTotalAll}"
                                }
                                findbugs(fp)
                            }
                            if (isMaven && archiveCheckstyle) {
                                def cp = [pattern: params?.checkstyle?.pattern ?: '**/target/checkstyle-result.xml']
                                if (params?.checkstyle?.unstableNewAll) {
                                    cp['unstableNewAll'] ="${params.checkstyle.unstableNewAll}"
                                }
                                if (params?.checkstyle?.unstableTotalAll) {
                                    cp['unstableTotalAll'] ="${params.checkstyle.unstableTotalAll}"
                                }
                                checkstyle(cp)
                            }
                            if (failFast && currentBuild.result == 'UNSTABLE') {
                                error 'There were test failures; halting early'
                            }
                            if (!jenkinsVersion) {
                                archiveArtifacts artifacts: artifacts, fingerprint: true
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    timestamps {
        return parallel(tasks)
    }
}
