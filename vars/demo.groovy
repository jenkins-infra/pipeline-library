def checkout() {
    commons.checkout("https://github.com/oleg-nenashev/job-restrictions-plugin", 'cobertura-profile')
}

def runParallel(int testParallelism=1, boolean runCobertura=false) {

    /* Request the test groupings.  Based on previous test results. */
    /* see https://wiki.jenkins-ci.org/display/JENKINS/Parallel+Test+Executor+Plugin and demo on github
    /* Using arbitrary parallelism of 4 and "generateInclusions" feature added in v1.8. */
    def splits = splitTests parallelism: [$class: 'CountDrivenParallelism', size: testParallelism], generateInclusions: true

    /* Create dictionary to hold set of parallel test executions. */
    def testGroups = [:]

    for (int i = 0; i < splits.size(); i++) {
        def splitNo = i
        def split = splits[i]

        /* Loop over each record in splits to prepare the testGroups that we'll run in parallel. */
        /* Split records returned from splitTests contain { includes: boolean, list: List<String> }. */
        /*     includes = whether list specifies tests to include (true) or tests to exclude (false). */
        /*     list = list of tests for inclusion or exclusion. */
        /* The list of inclusions is constructed based on results gathered from */
        /* the previous successfully completed job. One additional record will exclude */
        /* all known tests to run any tests not seen during the previous run.  */
        testGroups["split-${splitNo}"] = {  // example, "split3"
            node('linux') {
                demo.checkout()

                def command = "mvn clean verify -DMaven.test.failure.ignore=true"

                /* Write includesFile or excludesFile for tests.  Split record provided by splitTests. */
                /* Tell Maven to read the appropriate file. */
                if (split.includes) {
                    writeFile file: "tmp/parallel-test-includes-${splitNo}.txt", text: split.list.join("\n")
                    command += " -Dsurefire.includesFile=tmp/parallel-test-includes-${splitNo}.txt"
                } else {
                    writeFile file: "tmp/parallel-test-excludes-${splitNo}.txt", text: split.list.join("\n")
                    command += " -Dsurefire.excludesFile=tmp/parallel-test-excludes-${splitNo}.txt"
                }

                if (runCobertura) {
                    command += " -Pcobertura"
                }

                sh command

                /* Archive the test results */
                junit '**/target/surefire-reports/TEST-*.xml'

                if (runCobertura) {
                    stash includes: 'target/coverage.xml', name: "coverage-${splitNo}.xml"
                }
            }
        }
    }
    parallel testGroups
}