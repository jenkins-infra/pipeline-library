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
