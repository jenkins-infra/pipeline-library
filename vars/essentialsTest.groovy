def call(Map params = [:]) {
    def baseDir = params.containsKey('baseDir') ? params.baseDir : "."
    def metadataFile = params.containsKey('metadataFile') ? params.metadataFile : "essentials.yml"
    def labels = params.containsKey('labels') ? params.labels : "docker && highmem"

    node(labels) {
        stage("Checkout") {
            infra.checkout()
        }

        dir(baseDir) {
            def metadataPath = "${pwd()}/${metadataFile}"
            metadata = readYaml(file: metadataPath)

            def customBOM = "${pwd tmp: true}/custom.bom.yml"
            def customWAR = "${pwd tmp: true}/custom.war"
            def customWarURI = "file://" + customWAR

            stage("Build Custom WAR") {
                customWARPackager.build(metadataPath, customWAR, customBOM, mvnSettingsFile)
            }

            if (!metadata.ath.disabled) {
                stage("Run ATH") {
                    dir("ath") {
                        runATH jenkins: customWarURI, metadataFile: metadataPath
                    }
                }
            }

            if (!metadata.ath.disabled) {
                stage("Run PCT") {
                    runPCT jenkins: customWarURI, metadataFile: metadataPath
                }
            }
        }
    }
}
