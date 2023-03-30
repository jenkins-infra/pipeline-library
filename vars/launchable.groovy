def install() {
  if (isUnix()) {
    sh '''
        python3 -m venv launchable
        launchable/bin/pip --require-virtualenv --no-cache-dir install -U setuptools wheel
        launchable/bin/pip --require-virtualenv --no-cache-dir install launchable
       '''.stripIndent()
  } else {
    error 'Launchable installation is not yet implemented for Windows'
  }
}

def call(String args) {
  withCredentials([string(credentialsId: 'launchable-prototype', variable: 'LAUNCHABLE_TOKEN')]) {
    if (isUnix()) {
      sh 'launchable/bin/launchable ' + args
    } else {
      error 'The Launchable CLI is not yet implemented for Windows'
    }
  }
}
