def call(Map params = [:]) {
  def baseDir = params.containsKey('baseDir') ? params.baseDir : '.'
  def metadataFile = params.containsKey('metadataFile') ? params.metadataFile : 'essentials.yml'
  def labels = params.containsKey('labels') ? params.labels : 'docker-highmem'
  def testPluginResolution

  node(labels) {
    stage('Checkout') {
      infra.checkoutSCM()
    }

    dir(baseDir) {
      def metadataPath = "${pwd()}/${metadataFile}"
      def configData = readYaml(file: metadataPath)
      testPluginResolution = configData.flow?.ath?.testPluginResolution?.skipOnUnmetDependencies ? 'skipOnInvalid' : 'failOnInvalid'


      def customBOM = "${pwd tmp: true}/custom.bom.yml"
      def customWAR = "${pwd tmp: true}/custom.war"
      def customWarURI = "file://" + customWAR

      stage('Build Custom WAR') {
        customWARPackager.build(metadataPath, customWAR, customBOM)
      }

      if (configData.ath != null && !configData.ath.disabled) {
        stage('Run ATH') {
          dir('ath') {
            runATH jenkins: customWarURI, metadataFile: metadataPath, javaOptions: ['--no-transfer-progress', testPluginResolution]
          }
        }
      }

      if (configData.pct != null && !configData.pct.disabled) {
        stage('Run PCT') {
          runPCT jenkins: customWarURI, metadataFile: metadataPath
        }
      }
    }
  }
}
