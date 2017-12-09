node('linux') {
    stage("Checkout") {
        demo.checkout()
    }
    try {
        stage("Build") {
            commons.withErrorHandlers(10) {
                sh "mvn clean verify -DskipTests"
            }
        }
    } finally {
        stage("Archive") {
            findbugs pattern: "**/target/findbugsXml.xml"
        }
    }

    demo.runParallel(2, true)
    cobertura.mergeStashes(2)
}
