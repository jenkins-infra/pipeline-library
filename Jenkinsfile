#!/usr/bin/env groovy
pipeline {
    agent {
        label "java"
    }
    tools {
        maven 'mvn'
        jdk 'jdk8'
    }
    options {
        timestamps()
    }
    stages {
        stage('Test') {
            steps {
                sh 'mvn -B clean test'
            }
            post {
                always {
                    junit(keepLongStdio: true, testResults: 'target/surefire-reports/TEST-*.xml')
                }
            }
        }
    }
}
