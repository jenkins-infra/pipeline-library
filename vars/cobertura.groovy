/**
 * Publishes Cobertura report.
 * @param file Report File to publish
 * @param failUnhealthy Fail if the coverage is unhealthy
 * @param failUnstable Fail if the build is unstable
 * @return nothing
 */
def call(String file = target/coverage.xml, boolean failUnhealthy=false, boolean failUnstable=false) {
    step([$class: 'CoberturaPublisher',
          autoUpdateHealth: false,
          autoUpdateStability: false,
          coberturaReportFile: file,
          failNoReports: false,
          failUnhealthy: failUnhealthy,
          failUnstable: failUnstable,
          maxNumberOfBuilds: 0,
          onlyStable: false,
          sourceEncoding: 'ASCII',
          zoomCoverageChart: false]
    )
}

def mergeStashes(int testParallelism) {
    node('linux') {
        for (int I = 0; I < testParallelism; i++) {
            unstash"coverage-${i}.xml"
        }
        cobertura.merge("coverage-*.xml", "coverage-merged.xml")
        cobertura(file: "coverage-merged.xml")
    }
}

def merge(String pattern, String out) {
    //TODO: Write your own impl
    sh "cp coverage-1.xml ${out}"
}
