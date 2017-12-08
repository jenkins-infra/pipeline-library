node('linux') {
    stage("Checkout") {
        demo.checkout()
    }
    try {
        stage("Build") {
            sh "mvn clean verify -Dmaven.test.failure.ignore -Pcoverage"
        }
    } finally {
        stage("Archive") {
            junit "target/**/TEST-*.xml"
            findbugs pattern: "**/target/findbugsXml.xml"
            cobertura("target/coverage.xml")
        }
    }
}
