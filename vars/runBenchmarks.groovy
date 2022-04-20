#!/usr/bin/env groovy

/**
 * Run JMH benchmarks and archives results
 * @param artifacts archive these JMH reports
 * @since TODO
 */
def call(String artifacts = null) {
  lock('runBenchmarks') {
    node('highmem') {

      stage('Checkout repo') {
        infra.checkoutSCM()
      }

      stage('Run Benchmarks') {
        List<String> mvnOptions = ['test', '-P', 'jmh-benchmark']
        infra.runMaven(mvnOptions)
      }

      stage('Archive reports') {
        if (artifacts) {
          archiveArtifacts artifacts: artifacts
        } else {
          echo 'No artifacts to archive, skipping...'
        }
      }
    }
  }
}
