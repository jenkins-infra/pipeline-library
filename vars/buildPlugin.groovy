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
    def testParallelism = params.containsKey('testParallelism') ? params.testParallelism : 1
    def projectType = params.containsKey('projectType') ? params.projectType : null
    Map tasks = [failFast: failFast]

    boolean isMaven
    //TODO: probably it makes sense to default to Maven instead
    if (projectType == null) {
        stage("Determine the project type") {
            timeout(10) {
                node("docker") {
                    commonSteps.checkout(repo)
                    if (fileExists('pom.xml')) {
                        projectType = "maven"
                        isMaven = true
                    } else if (fileExists('build.gradle')) {
                        projectType = "gradle"
                    } else {
                        error("Cannot determine the project type")
                    }
                }
            }
        }
    }
    echo "Project type is ${projectType}"

    String firstStageIdentifier
    for (int i = 0; i < platforms.size(); ++i) {
        for (int j = 0; j < jdkVersions.size(); ++j) {
            for (int k = 0; k < jenkinsVersions.size(); ++k) {
                String label = platforms[i]
                String jdk = jdkVersions[j]
                String jenkinsVersion = jenkinsVersions[k]
                String stageIdentifier = "${label}-${jdk}${jenkinsVersion ? '-' + jenkinsVersion : ''}"
                boolean first = i == 0 && j == 0 && k == 0
                if (first) {
                    firstStageIdentifier = stageIdentifier
                }

                tasks[stageIdentifier] = {
                    if (isMaven) {
                        boolean runFindbugs = first && params?.findbugs?.run
                        boolean runCheckstyle = first && params?.checkstyle?.run
                        boolean runCobertura = first && params?.cobertura?.run
                        boolean archiveFindbugs = first && params?.findbugs?.archive
                        boolean archiveCheckstyle = first && params?.checkstyle?.archive

                        buildMavenPluginPom(
                            stageIdentifier: stageIdentifier,
                            label: label,
                            jdk: jdk,
                            jenkinsVersion: jenkinsVersion,
                            repo: repo,
                            testParallelism: testParallelism,
                            runFindbugs: runFindbugs,
                            archiveFindbugs: archiveFindbugs,
                            runCheckstyle: runCheckstyle,
                            archiveCheckstyle: archiveCheckstyle,
                            runCobertura: runCobertura);
                    } else {
                        //TODO: add support of Jenkins Version
                         buildGradleComponent(stageIdentifier, label, jdk, failFast)
                    }
                }
            }
        }
    }

    timestamps {
        if (tasks.size() > 2) { // tasks + failFast
            return parallel(tasks)
        } else {
            tasks[firstStageIdentifier].call()
        }
    }
}
