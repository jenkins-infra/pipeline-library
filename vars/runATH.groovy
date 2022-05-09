#!/usr/bin/env groovy

/**
 * Wrapper for running the ATH see README and runATH.txt for full documentation
 */

def call(Map params = [:]) {
  def athUrl = params.get('athUrl', 'https://github.com/jenkinsci/acceptance-test-harness.git')
  def athRevision = params.get('athRevision', 'master')
  def metadataFile = params.get('metadataFile', 'essentials.yml')
  def jenkins = params.get('jenkins', 'latest')
  def jdks = params.get('jdks', [8])
  def athContainerImageTag = params.get('athImage', "jenkins/ath")
  def configFile = params.get('configFile', null)
  def defaultJavaOptions = params.get('javaOptions', [])

  def defaultCategory = "org.jenkinsci.test.acceptance.junit.SmokeTest"
  def metadata
  def athContainerImage
  def isLocalATH
  def isVersionNumber

  def athSourcesFolder = 'athSources'

  def supportedBrowsers = ['firefox']
  def supportedJdks = [8, 11]

  def skipExecution = false

  def localPluginsStashName = env.RUN_ATH_LOCAL_PLUGINS_STASH_NAME ?: 'localPlugins'

  infra.ensureInNode(env.RUN_ATH_SOURCES_AND_VALIDATION_NODE ?: 'docker-highmem') {
    if (!fileExists(metadataFile)) {
      echo "Skipping ATH execution because the metadata file does not exist. Current value is ${metadataFile}."
      skipExecution = true
      return
    }

    stage('Getting ATH sources and Jenkins war') {
      // Start validation
      metadata = readYaml(file: metadataFile)?.ath
      if (metadata == null) {
        echo 'Skipping ATH execution because the metadata file does not contain an ath section'
        skipExecution = true
        return
      }
      if (metadata == 'default') {
        echo 'Using default configuration for ATH'
        metadata = [:]
      } else if (metadata.browsers == null) {
        echo "The provided metadata file does not include the browsers property, using firefox as default"
      }
      // Allow to override athUrl and athRevision from metadata file
      athUrl = metadata.athUrl ?: athUrl
      isLocalATH = athUrl.startsWith("file://")
      athRevision = metadata.athRevision ?: athRevision
      athContainerImageTag = metadata.athImage ?: athContainerImageTag

      // Allow override of jenkins version from metadata file
      jenkins = metadata.jenkins ?: jenkins
      isVersionNumber = (jenkins =~ /^\d+([.]\d+)*$/).matches()

      // Allow override of JDK version from metadata file
      jdks = metadata.jdks ?: jdks

      if (!isLocalATH) {
        echo 'Checking connectivity to ATH sources…'
        sh "git ls-remote --exit-code -h ${athUrl}"
      }
      infra.stashJenkinsWar(jenkins)
      // Validation ended

      // ATH
      if (isLocalATH) { // Deal with already existing ATH sources
        athSourcesFolder = athUrl - "file://"
      } else {
        dir(athSourcesFolder) {
          checkout changelog: true, poll: false, scm: [$class           : 'GitSCM', branches: [[name: athRevision]],
            userRemoteConfigs: [[url: athUrl]]]
        }
      }
      dir(athSourcesFolder) {
        // We may need to run things in parallel later, so avoid several checkouts
        stash name: 'athSources'
      }

    }
  }

  infra.ensureInNode(env.RUN_ATH_DOCKER_NODE ?: 'docker-highmem') {
    if (skipExecution) {
      return
    }
    stage('Running ATH') {
      dir('athSources') {
        deleteDir()
        unstash name: 'athSources'
        if (athContainerImageTag == 'local') {
          echo "'local' ATH container image specified, building it"
          def uid = sh(script: 'id -u', returnStdout: true)
          def gid = sh(script: 'id -g', returnStdout: true)
          athContainerImage = docker.build('jenkins/ath', "--build-arg=uid='$uid' --build-arg=gid='$gid' -f src/main/resources/ath-container/Dockerfile .")
        } else {
          echo "No building ATH docker container image. Using ${athContainerImageTag} as specified"
          athContainerImage = docker.image(athContainerImageTag)
          athContainerImage.pull() //Use the latest available version
        }
      }

      def testsToRun = metadata.tests?.join(",")
      def categoriesToRun = metadata.categories?.join(",")
      def browsers = metadata.browsers ?: ['firefox']
      def failFast = metadata.failFast ?: false
      def rerunCount = metadata.rerunFailingTestsCount ?: 0
      // Elvis fails in case useLocalSnapshots == false in metadata File
      def localSnapshots = metadata.useLocalSnapshots != null ? metadata.useLocalSnapshots : true

      if (testsToRun == null && categoriesToRun == null) {
        categoriesToRun = defaultCategory
      }

      // Shorthand for running all tests
      if (testsToRun == 'all') {
        testsToRun = categoriesToRun = null
      }

      def testingbranches = ['failFast': failFast]
      for (jdk in jdks) {
        if (supportedJdks.contains(jdk)) {
          def currentJdk = jdk
          def javaOptions = defaultJavaOptions.clone()
          //Add shm-size to avoid selenium.WebDriverException exceptions like 'Failed to decode response from marionette' and webdriver closed
          def containerArgs = "-v /var/run/docker.sock:/var/run/docker.sock -u ath-user --shm-size 2g"

          if (configFile) {
            containerArgs += " -e CONFIG=../${configFile}" // ATH runs are executed in a subfolder, hence path needs to take that into account
          }

          // Add options for jdks
          if (currentJdk > 8) {
            // Add environment variable
            containerArgs += " -e java_version=${currentJdk}"
          }

          for (browser in browsers) {
            if (supportedBrowsers.contains(browser)) {
              def currentBrowser = browser

              def commandBase = "./run.sh ${currentBrowser} ./jenkins.war -B -ntp -Dmaven.test.failure.ignore=true -DforkCount=1 -Dsurefire.rerunFailingTestsCount=${rerunCount}"

              if (testsToRun) {
                testingbranches["ATH individual tests-${currentBrowser}-jdk${currentJdk}"] = {
                  dir("test${currentBrowser}jdk${currentJdk}") {
                    def discriminator = "-Dtest=${testsToRun}"
                    test(discriminator, commandBase, localSnapshots, localPluginsStashName, containerArgs, athContainerImage, javaOptions)
                  }
                }
              }
              if (categoriesToRun) {
                testingbranches["ATH categories-${currentBrowser}-jdk${currentJdk}"] = {
                  dir("categories${currentBrowser}jdk${currentJdk}") {
                    def discriminator = "-Dgroups=${categoriesToRun}"
                    test(discriminator, commandBase, localSnapshots, localPluginsStashName, containerArgs, athContainerImage, javaOptions)
                  }
                }
              }
              if (testsToRun == null && categoriesToRun == null) {
                testingbranches["ATH ${currentBrowser}-jdk${currentJdk}"] = {
                  dir("ath${currentBrowser}${currentJdk}") {
                    def discriminator = ''
                    test(discriminator, commandBase, localSnapshots, localPluginsStashName, containerArgs, athContainerImage, javaOptions)
                  }
                }
              }

            } else {
              echo "${browser} is not yet supported"
            }
          }
        } else {
          echo "${jdk} is not yet supported"
        }
      }

      parallel testingbranches
    }
  }
}

private void test(discriminator, commandBase, localSnapshots, localPluginsStashName, containerArgs, athContainerImage, javaOptions) {
  unstashResources(localSnapshots, localPluginsStashName)
  athContainerImage.inside(containerArgs) {
    realtimeJUnit(testResults: 'target/surefire-reports/TEST-*.xml', testDataPublishers: [[$class: 'AttachmentPublisher']]) {
      /*
       If you want to compile with java > 8 and have all needed, do it
       If you want to compile with java > 8 and you lack the set-java script, FAILURE
       If other case (java8 and lack set-java), write a message in the log
       */
      def command = '''
            if [ -x ./set-java.sh ] && [ -n "$java_version" ] && [ "$java_version" -gt "8" ]; then
                ./set-java.sh $java_version;
            elif [ -n "$java_version" ] && [ "$java_version" -gt "8" ] && [ ! -x ./set-java.sh ]; then
                echo "ERROR: ./set-java.sh not found because you are using old ATH sources and \\$java_version = $java_version specified. We cannot run on this JDK";
                exit 1;
            elif [ ! -x ./set-java.sh ]; then
                echo "INFO: ./set-java.sh not found because you are using old ATH sources, please consider to update ATH sources and docker image";
            fi

            eval "$(./vnc.sh)" \
            && export DISPLAY=$BROWSER_DISPLAY \
            && export SHARED_DOCKER_SERVICE=true \
            && export EXERCISEDPLUGINREPORTER=textfile \
            && \
            '''

      command += prepareCommand(commandBase, discriminator, localSnapshots, localPluginsStashName)
      if (!javaOptions.isEmpty()) {
        command = """export JAVA_OPTS="${javaOptions.join(' ')}" && ${command}"""
      }

      sh command
    }
  }
}

private String prepareCommand(commandBase, discriminator, localSnapshots, localPluginsStashName) {
  def command = commandBase + " ${discriminator}"
  if (localSnapshots && localPluginsStashName) {
    command = "LOCAL_JARS=${getLocalPluginsList()} " + command
  }
  command
}

private void unstashResources(localSnapshots, localPluginsStashName) {
  unstash name: 'athSources'
  unstash name: 'jenkinsWar'
  dir('localPlugins') {
    if (localSnapshots && localPluginsStashName) {
      unstash name: localPluginsStashName
    }
  }
}

private String getLocalPluginsList() {
  return sh(script : "ls -p -d -1 localPlugins/*.* | tr '\n' ':'| sed 's/.\$//'", returnStdout: true).trim()
}
