node('linux') {
    stage("Checkout") {
        demo.checkout()
    }
    try {
        stage("Build") {
            commons.withErrorHandlers(10) {
                sh "mvn clean verify -Djava.level=9 -Dmaven.test.failure.ignore -Pcoverage"
            }
        }
    } finally {
        stage("Archive") {
            junit "target/**/TEST-*.xml"
            findbugs pattern: "**/target/findbugsXml.xml"
            cobertura("target/coverage.xml")
        }
    }
}
