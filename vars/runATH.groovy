#!/usr/bin/env groovy

/**
 * Simple wrapper for running the ATH
 */

def call(Map params = [:]) {
    def athUrl = params.get('athUrl', 'https://github.com/jenkinsci/acceptance-test-harness.git')
    def athRevision = params.get('athRevision', 'master')
    def metadataFile = params.get('metadataFile', 'essentials.yml')
    def jenkins = params.get('jenkins','latest')
    def platforms = params.get('platforms', ['linux'])
}