#!/usr/bin/env groovy

/**
 * Builds a component, which implements Jenkins Plugin POM.
 * It may be either Jenkins plugin or module, depending on the packaging.
 *
 * @param stageIdentifier Stage identifier
 * @param label Node label to be used, {@code linux} by default
 * @param jdk JDK version to be used, {@code 8} by default
 * @param jenkinsVersion Version of Jenkins to be used. {@code null} if the default version in pom.xml should be used
 * @param repo Repository to be used for Git checkout. Use {@code null} for Multi-Branch
 * @param failFast Fail the build if one of the branches fails
 * @param testParallelism Number of parallel test builds, {@code 1} by default
 */
def call(String stageIdentifier, String label = "linux", String jdk = "8", String jenkinsVersion = null,
             String repo = null, boolean failFast = true, int testParallelism = 1,
             boolean runFindbugs = true, boolean archiveFindbugs = true,
             boolean runCheckstyle = true, boolean archiveCheckstyle = true,
             boolean runCobertura = true) {

    node(label) {
        timeout(60) {
            boolean isParallelTestMode
            List<String> baseMavenParameters
            String testReports
            String artifacts

            def mavenEnvVars = ["PATH+MAVEN=${tool 'mvn'}/bin"];

            stage("Checkout (${stageIdentifier})") {
                commonSteps.checkout(repo)

                // Manage test parallelism
                if (testParallelism > 1) {
                    echo "Using parallel tests mode"
                }
                isParallelTestMode = testParallelism > 1

                // Prepare Maven defaults
                baseMavenParameters = [
                    '--batch-mode',
                    '--errors',
                    '--update-snapshots',
                    '-Dmaven.test.failure.ignore',
                ]
                if (jdk.toInteger() > 7 && infra.isRunningOnJenkinsInfra()) {
                    /* Azure mirror only works for sufficiently new versions of the JDK due to Letsencrypt cert */
                    def settingsXml = "${pwd tmp: true}/settings-azure.xml"
                    writeFile file: settingsXml, text: libraryResource('settings-azure.xml')
                    baseMavenParameters += "-s $settingsXml"
                }
                if (jenkinsVersion) {
                    baseMavenParameters += "-Djenkins.version=${jenkinsVersion}"
                }

                // Artifacts to publish
                testReports = '**/target/surefire-reports/**/*.xml'
                artifacts = '**/target/*.hpi,**/target/*.jpi'
            }

            stage("Build (${stageIdentifier})") {
                List<String> mavenOptions = new ArrayList<>(baseMavenParameters)
                List<String> profiles = [];
                if (params?.findbugs?.run || params?.findbugs?.archive) {
                    mavenOptions += isParallelTestMode ? "-Dfindbugs.skip=true" : "-Dfindbugs.failOnError=false"
                }
                if (params?.checkstyle?.run || params?.checkstyle?.archive) {
                    mavenOptions += isParallelTestMode ? "-Dcheckstyle.skip=true" : "-Dcheckstyle.failOnViolation=false -Dcheckstyle.failsOnError=false"
                }
                if (isParallelTestMode) {
                    mavenOptions += "-DskipTests"
                    profiles << "!skip-findbugs-with-tests"
                }
                mavenOptions += "clean install"
                if (runFindbugs && !isParallelTestMode) {
                    mavenOptions += "findbugs:findbugs"
                }
                if (runCheckstyle && !isParallelTestMode) {
                    mavenOptions += "checkstyle:checkstyle"
                }
                if (runCobertura && !isParallelTestMode) {
                    profiles << "coverage"
                }

                commonSteps.runWithJava("mvn ${mavenOptions.join(' ')}", jdk, mavenEnvVars)
            }

            if (isParallelTestMode) {
                stage("Test (${stageIdentifier})") {
                    List<String> mavenOptions = new ArrayList<>(baseMavenParameters)
                    mavenOptions += "-Dfindbugs.skip=true"
                    mavenOptions += "-Dcheckstyle.skip=true"
                    String prefix = "${label}-${jdk}"
                    runParallelTests(prefix, label, mavenOptions, jdk, ((boolean)repo) ? repo : null, testParallelism, mavenEnvVars)
                }
            }

            stage("Archive (${stageIdentifier})") {

                if (!isParallelTestMode) {
                    junit testReports
                    // TODO do this in a finally-block so we capture all test results even if one branch aborts early
                }
                if (archiveFindbugs) {
                    def fp = [pattern: params?.findbugs?.pattern ?: '**/target/findbugsXml.xml']
                    if (params?.findbugs?.unstableNewAll) {
                        fp['unstableNewAll'] ="${params.findbugs.unstableNewAll}"
                    }
                    if (params?.findbugs?.unstableTotalAll) {
                        fp['unstableTotalAll'] ="${params.findbugs.unstableTotalAll}"
                    }
                    findbugs(fp)
                }
                if (archiveCheckstyle) {
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
                archiveArtifacts artifacts: artifacts, fingerprint: true
            }
        }
    }
}
