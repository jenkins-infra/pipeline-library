/**
 * Publishes Cobertura report.
 * @param coberturaReportFile Report File to publish
 * @param failUnhealthy Fail if the coverage is unhealthy
 * @param failUnstable Fail if the build is unstable
 * @return nothing
 */
def publish(String coberturaReportFile, boolean failUnhealthy=false, boolean failUnstable=false) {
    step([$class: 'CoberturaPublisher',
          autoUpdateHealth: false,
          autoUpdateStability: false,
          coberturaReportFile: 'target/coverage.xml',
          failNoReports: false,
          failUnhealthy: failUnhealthy,
          failUnstable: failUnstable,
          maxNumberOfBuilds: 0,
          onlyStable: false,
          sourceEncoding: 'ASCII',
          zoomCoverageChart: false]
    )
}
