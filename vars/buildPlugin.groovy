#!/usr/bin/env groovy

/**
 * Simple wrapper step for building a plugin
 */
def call(Map params = [:]) {
    // Faster build and reduces IO needs
    properties([
        durabilityHint('PERFORMANCE_OPTIMIZED'),
        buildDiscarder(logRotator(numToKeepStr: '5')),
    ])

    def platforms = params.containsKey('platforms') ? params.platforms : ['linux', 'windows']
    def jdkVersions = params.containsKey('jdkVersions') ? params.jdkVersions : [8]
    def jenkinsVersions = params.containsKey('jenkinsVersions') ? params.jenkinsVersions : [null]
    def repo = params.containsKey('repo') ? params.repo : null
    def failFast = params.containsKey('failFast') ? params.failFast : true
    Map tasks = [failFast: failFast]
    boolean publishingIncrementals = false
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
                        boolean doArchiveArtifacts = /* default platform */ label == platforms[0] && /* default baseline */ !jenkinsVersion
                        boolean incrementals // cf. JEP-305

                        stage("Checkout (${stageIdentifier})") {
                            infra.checkout(repo)
                            isMaven = fileExists('pom.xml')
                            incrementals = fileExists('.mvn/extensions.xml') &&
                                           readFile('.mvn/extensions.xml').contains('git-changelist-maven-extension')
                        }

                        String changelistF
                        String m2repo

                        stage("Build (${stageIdentifier})") {
                            String command
                            if (isMaven) {
                                m2repo = "${pwd tmp: true}/m2repo"
                                List<String> mavenOptions = [
                                        '--update-snapshots',
                                        "-Dmaven.repo.local=$m2repo",
                                        '-Dmaven.test.failure.ignore',
                                ]
                                if (incrementals) { // set changelist and activate produce-incrementals profile
                                    mavenOptions += '-Dset.changelist'
                                    if (doArchiveArtifacts) { // ask Maven for the value of -rc999.abc123def456
                                        changelistF = "${pwd tmp: true}/changelist"
                                        mavenOptions += "help:evaluate -Dexpression=changelist -Doutput=$changelistF"
                                    }
                                }
                                if (jenkinsVersion) {
                                    mavenOptions += "-Djenkins.version=${jenkinsVersion} -Daccess-modifier-checker.failOnError=false"
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
                                infra.runMaven(mavenOptions, jdk)
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
                                infra.runWithJava(command, jdk)
                            }
                        }

                        stage("Archive (${stageIdentifier})") {
                            String testReports
                            if (isMaven) {
                                testReports = '**/target/surefire-reports/**/*.xml'
                            } else {
                                testReports = '**/build/test-results/**/*.xml'
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
                            if (doArchiveArtifacts) {
                                if (incrementals) {
                                    String changelist = readFile(changelistF)
                                    dir(m2repo) {
                                        fingerprint '**/*-rc*.*/*-rc*.*' // includes any incrementals consumed
                                        archiveArtifacts artifacts: "**/*$changelist/*$changelist*",
                                                         excludes: '**/*.lastUpdated',
                                                         allowEmptyArchive: true // in case we forgot to reincrementalify
                                    }
                                    publishingIncrementals = true
                                } else {
                                    String artifacts
                                    if (isMaven) {
                                        artifacts = '**/target/*.hpi,**/target/*.jpi'
                                    } else {
                                        artifacts = '**/build/libs/*.hpi,**/build/libs/*.jpi'
                                    }
                                    archiveArtifacts artifacts: artifacts, fingerprint: true
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    timestamps {
        parallel(tasks)
        if (publishingIncrementals) {
            infra.maybePublishIncrementals()
        }
    }
}
