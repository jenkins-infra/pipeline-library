node('linux') {
    demo.checkout()
    sh "mvn clean verify -Dmaven.test.failure.ignore -Pcoverage"

    junit "target/**/TEST-*.xml"
    findbugs pattern: "**/target/findbugsXml.xml"
    cobertura("target/coverage.xml")
}
