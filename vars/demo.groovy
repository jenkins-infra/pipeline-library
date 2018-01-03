def checkout() {
    commons.checkout("https://github.com/oleg-nenashev/job-restrictions-plugin", 'cobertura-profile')
}

def runParallel(int testParallelism=1, boolean runCobertura=false) {

    def splits = splitTests parallelism: [$class: 'CountDrivenParallelism', size: testParallelism], generateInclusions: true
    /* Create dictionary to hold set of parallel test executions. */
    def testGroups = [:]

    for (int i = 0; i < splits.size(); i++) {
        def splitNo = i
        def split = splits[i]

        testGroups["split-${splitNo}"] = {  // example, "split3"
            node('linux') {
                commons.checkout("https://github.com/oleg-nenashev/job-restrictions-plugin", 'cobertura-profile')

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