def install() {
  if (isUnix()) {
    catchError(buildResult: 'SUCCESS', catchInterruptions: false, message: 'Failed to install Launchable; continuing build.') {
      sh '''
          python3 -m venv launchable
          launchable/bin/pip --require-virtualenv --no-cache-dir install -U setuptools wheel
          launchable/bin/pip --require-virtualenv --no-cache-dir install launchable
         '''.stripIndent()
    }
  } else {
    error 'Launchable installation is not yet implemented for Windows'
  }
}

def call(String args) {
  if (isUnix()) {
    catchError(buildResult: 'SUCCESS', catchInterruptions: false, message: 'Failed to run Launchable; continuing build.') {
      sh 'launchable/bin/launchable ' + args
    }
  } else {
    error 'The Launchable CLI is not yet implemented for Windows'
  }
}
