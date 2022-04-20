#!/usr/bin/env groovy
pipeline {
  agent {
    label 'maven-11'
  }
  options {
    timestamps()
  }
  stages {
    stage('Test') {
      steps {
        ansiColor('xterm') {
          sh 'mvn -Dstyle.color=always --no-transfer-progress -B clean test'
        }
      }
      post {
        always {
          junit(keepLongStdio: true, testResults: 'target/surefire-reports/TEST-*.xml')
        }
      }
    }
  }
}
