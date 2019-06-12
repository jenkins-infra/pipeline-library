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
        stage('Checkout') {
            steps {
                deleteDir()
                checkout scm
            }
        }
        stage('Test') {
            steps {
                sh 'mvn clean test'
            }
            post {
                always {
                    junit(keepLongStdio: true, testResults: "target/surefire-reports/junit-*.xml,target/surefire-reports/TEST-*.xml")
                }
            }
        }
    }
}
