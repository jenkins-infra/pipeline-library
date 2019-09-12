#!/usr/bin/env groovy

//TODO(oleg_nenashev): This thing is not simple anymore. I suggest reworking it to a config YAML
// which would be compatible with essentials.yml (INFRA-1673)
/**
 * Simple wrapper step for building a plugin
 */
def call(Map params = [:]) {
    // Faster build and reduces IO needs
    properties([
        durabilityHint('PERFORMANCE_OPTIMIZED'),
        buildDiscarder(logRotator(numToKeepStr: '5')),
    ])

    def repo = params.containsKey('repo') ? params.repo : null
    def failFast = params.containsKey('failFast') ? params.failFast : true
    def timeoutValue = params.containsKey('timeout') ? params.timeout : 60
    def useAci = params.containsKey('useAci') ? params.useAci : false
    def forceAci = params.containsKey('forceAci') ? params.forceAci : false
    if(timeoutValue > 180) {
      echo "Timeout value requested was $timeoutValue, lowering to 180 to avoid Jenkins project's resource abusive consumption"
      timeoutValue = 180
    }

    boolean publishingIncrementals = false
    boolean archivedArtifacts = false
    Map tasks = [failFast: failFast]
    getConfigurations(params).each { config ->
        String label = config.platform
        String jdk = config.jdk
        String jenkinsVersion = config.jenkins
        String javaLevel = config.javaLevel

        String stageIdentifier = "${label}-${jdk}${jenkinsVersion ? '-' + jenkinsVersion : ''}"
        boolean first = tasks.size() == 1
        boolean runFindbugs = first && params?.findbugs?.run
        boolean runCheckstyle = first && params?.checkstyle?.run
        boolean archiveFindbugs = first && params?.findbugs?.archive
        boolean archiveCheckstyle = first && params?.checkstyle?.archive
        boolean skipTests = params?.tests?.skip
        boolean reallyUseAci = (useAci && label == 'linux') || forceAci
        boolean addToolEnv = !reallyUseAci

        if(reallyUseAci) {
            String aciLabel = jdk == '8' ? 'maven' : 'maven-11'
            if(label == 'windows') {
                aciLabel += "-windows"
            }
            label = aciLabel
        }

        tasks[stageIdentifier] = {
            node(label) {
                boolean isMaven
                // Archive artifacts once with pom declared baseline
                boolean doArchiveArtifacts = !jenkinsVersion && !archivedArtifacts
                boolean incrementals // cf. JEP-305
                String changelistF
                String m2repo
                try {
                    timeout(timeoutValue) {
                        if (doArchiveArtifacts) {
                            archivedArtifacts = true
                        }

                        stage("Checkout (${stageIdentifier})") {
                            infra.checkout(repo)
                            isMaven = fileExists('pom.xml')
                            incrementals = fileExists('.mvn/extensions.xml') &&
                                    readFile('.mvn/extensions.xml').contains('git-changelist-maven-extension')
                        }

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
                                if (javaLevel) {
                                    mavenOptions += "-Djava.level=${javaLevel}"
                                }
                                if (params?.findbugs?.run || params?.findbugs?.archive) {
                                    mavenOptions += "-Dfindbugs.failOnError=false"
                                }
                                if (skipTests) {
                                    mavenOptions += "-DskipTests"
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
                                infra.runMaven(mavenOptions, jdk, null, null, addToolEnv)
                            } else {
                                echo "WARNING: Gradle mode for buildPlugin() is deprecated, please use buildPluginWithGradle()"
                                List<String> gradleOptions = [
                                        '--no-daemon',
                                        'cleanTest',
                                        'build',
                                ]
                                command = "gradlew ${gradleOptions.join(' ')}"
                                if (isUnix()) {
                                    command = "./" + command
                                }
                                infra.runWithJava(command, jdk, null, addToolEnv)
                            }
                        }
                    }
                } finally {
                    stage("Archive (${stageIdentifier})") {
                        if (!skipTests) {
                            String testReports
                            if (isMaven) {
                                testReports = '**/target/surefire-reports/**/*.xml,**/target/failsafe-reports/**/*.xml'
                            } else {
                                testReports = '**/build/test-results/**/*.xml'
                            }
                            junit testReports
                        }
                        if (isMaven && archiveFindbugs) {
                            def fp = [pattern: params?.findbugs?.pattern ?: '**/target/findbugsXml.xml']
                            if (params?.findbugs?.unstableNewAll) {
                                fp['unstableNewAll'] = "${params.findbugs.unstableNewAll}"
                            }
                            if (params?.findbugs?.unstableTotalAll) {
                                fp['unstableTotalAll'] = "${params.findbugs.unstableTotalAll}"
                            }
                            findbugs(fp)
                        }
                        if (isMaven && archiveCheckstyle) {
                            def cp = [pattern: params?.checkstyle?.pattern ?: '**/target/checkstyle-result.xml']
                            if (params?.checkstyle?.unstableNewAll) {
                                cp['unstableNewAll'] = "${params.checkstyle.unstableNewAll}"
                            }
                            if (params?.checkstyle?.unstableTotalAll) {
                                cp['unstableTotalAll'] = "${params.checkstyle.unstableTotalAll}"
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
                    deleteDir()

                    if (hasDockerLabel()) {
                        if(isUnix()) {
                            sh 'docker system prune --force --all || echo "Failed to cleanup docker images"'
                        } else {
                            bat 'docker system prune --force --all || echo "Failed to cleanup docker images"'
                        }
                    }
                }
            }
        }
    }

    parallel(tasks)
    if (publishingIncrementals) {
        infra.maybePublishIncrementals()
    }
}

boolean hasDockerLabel() {
    env.NODE_LABELS?.contains("docker")
}

List<Map<String, String>> getConfigurations(Map params) {
    boolean explicit = params.containsKey("configurations")
    boolean implicit = params.containsKey('platforms') || params.containsKey('jdkVersions') || params.containsKey('jenkinsVersions')

    if (explicit && implicit) {
        error '"configurations" option can not be used with either "platforms", "jdkVersions" or "jenkinsVersions"'
    }


    def configs = params.configurations
    configs.each { c ->
        if (!c.platform) {
            error("Configuration field \"platform\" must be specified: $c")
        }
        if (!c.jdk) {
            error("Configuration filed \"jdk\" must be specified: $c")
        }
    }

    if (explicit) return params.configurations

    def platforms = params.containsKey('platforms') ? params.platforms : ['linux', 'windows']
    def jdkVersions = params.containsKey('jdkVersions') ? params.jdkVersions : [8]
    def jenkinsVersions = params.containsKey('jenkinsVersions') ? params.jenkinsVersions : [null]

    def ret = []
    for (p in platforms) {
        for (jdk in jdkVersions) {
            for (jenkins in jenkinsVersions) {
                ret << [
                        "platform": p,
                        "jdk": jdk,
                        "jenkins": jenkins,
                        "javaLevel": null   // not supported in the old format
                ]
            }
        }
    }
    return ret
}

/**
 * Get recommended configurations for testing.
 * Includes testing Java 8 and 11 on the newest LTS.
 */
static List<Map<String, String>> recommendedConfigurations() {
    def recentLTS = "2.164.1"
    def configurations = [
        [ platform: "linux", jdk: "8", jenkins: null ],
        [ platform: "windows", jdk: "8", jenkins: null ],
        [ platform: "linux", jdk: "8", jenkins: recentLTS, javaLevel: "8" ],
        [ platform: "windows", jdk: "8", jenkins: recentLTS, javaLevel: "8" ],
        [ platform: "linux", jdk: "11", jenkins: recentLTS, javaLevel: "8" ],
        [ platform: "windows", jdk: "11", jenkins: recentLTS, javaLevel: "8" ]
    ]
    return configurations
}
