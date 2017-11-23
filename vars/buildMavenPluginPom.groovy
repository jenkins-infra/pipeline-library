#!/usr/bin/env groovy

/**
 * Builds a component, which implements Jenkins Plugin POM.
 * It may be either Jenkins plugin or module, depending on the packaging.
 *
 * @param stageIdentifier Textual identifier for operations
 * @param label Node label, which should be used for the build
 * @param jdk JDK version, which should be used for the build. E.g. {@code 8}
 * @param jenkinsVersion Jenkins version, which should be used for the build. {@code null} for default defined in POM.xml
 * @param repo Repository to be used. {@code null} can be used in Multi-branch build
 */
Boolean call(Map params = [:]) {

    def stageIdentifier = params.stageIdentifier
    def label = params.containsKey('label') ? params.label : 'linux'
    def jdk = params.containsKey('jdk') ? params.jdk : 8
    def jenkinsVersion = params.containsKey('jenkinsVersion') ? params.jenkinsVersion : [null]
    def repo = params.containsKey('repo') ? params.repo : null
    def failFast = params.containsKey('failFast') ? params.failFast : true
    def testParallelism = params.containsKey('testParallelism') ? params.testParallelism : 1
    boolean runFindbugs = params.containsKey('runFindbugs') ? params.runFindbugs : true
    boolean archiveFindbugs = params.containsKey('archiveFindbugs') ? params.archiveFindbugs : true
    boolean runCheckstyle = params.containsKey('runCheckstyle') ? params.runCheckstyle : true
    boolean archiveCheckstyle = params.containsKey('archiveCheckstyle') ? params.archiveCheckstyle : true
    boolean runCobertura = params.containsKey('runCobertura') ? params.runCobertura : true


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
    return true
}
