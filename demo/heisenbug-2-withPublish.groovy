node('linux') {
    demo.checkout()
    ssh "mvn clean verify -Dmaven.test.failure.ignore -Pcoverage"

    junit "target/**/TEST-*.xml"
    findbugs pattern: "**/target/findbugsXml.xml"
    cobertura("target/coverage.xml")
}
