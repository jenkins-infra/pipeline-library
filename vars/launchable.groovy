def install() {
  boolean alreadyInstalled = true
  if (isUnix()) {
    catchError(buildResult: 'SUCCESS', catchInterruptions: false, message: 'Failed to install Launchable; continuing build.') {
      if (sh(script: 'command -v launchable', returnStatus: true) != 0) {
        sh '''
            python3 -m venv launchable
            launchable/bin/pip --require-virtualenv --no-cache-dir install -U setuptools wheel
            launchable/bin/pip --require-virtualenv --no-cache-dir install launchable
            ln -s launchable/bin/launchable /urs/local/bin/launchable
            '''.stripIndent()
        alreadyInstalled = false
      }
    }
  } else {
    catchError(buildResult: 'SUCCESS', catchInterruptions: false, message: 'Failed to install Launchable; continuing build.') {
      if (bat(script: 'launchable --version 2>NUL || echo "NOT_INSTALLED"', returnStdout: true) == 'NOT_INSTALLED') {
        bat '''
            python.exe -m pip --no-cache-dir install --upgrade setuptools wheel pip
            python.exe -m pip --no-cache-dir install launchable
          '''.stripIndent()
        alreadyInstalled = false
      }
    }
  }
  if (alreadyInstalled) {
    echo 'NOTICE: Launchable is already installed.'
  }
}

def call(String args) {
  if (isUnix()) {
    catchError(buildResult: 'SUCCESS', catchInterruptions: false, message: 'Failed to run Launchable; continuing build.') {
      sh 'launchable ' + args
    }
  } else {
    catchError(buildResult: 'SUCCESS', catchInterruptions: false, message: 'Failed to run Launchable; continuing build.') {
      bat 'launchable ' + args
    }
  }
}
