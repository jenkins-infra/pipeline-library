#!/usr/bin/env groovy

/**
 * Run JMH benchmarks and archives results
 * @param artifacts archive these JMH reports
 * @since TODO
 */
def call(String artifacts = null) {
    // TODO: Use Lockable Resources plugin
    node('highmem') {

        stage('Checkout repo') {
            infra.checkout()
        }

        stage('Run Benchmarks') {
            List<String> mvnOptions = ['test', '-Dbenchmark']
            infra.runMaven(mvnOptions)
        }

        stage('Archive reports') {
            if (artifacts != null) {
                archiveArtifacts artifacts: artifacts
            } else {
                echo 'No artifacts to archive, skipping...'
            }
        }
    }
}
