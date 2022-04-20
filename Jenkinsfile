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
        sh 'mvn --no-transfer-progress -B clean verify'
      }
      post {
        always {
          junit(keepLongStdio: true, testResults: 'target/surefire-reports/TEST-*.xml')
        }
      }
    }
  }
}
