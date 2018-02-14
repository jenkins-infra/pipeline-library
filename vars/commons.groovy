Object checkout(String repo = null, String branch = "master") {
    if (env.BRANCH_NAME) {
        // Multi-branch Pipeline
        checkout scm
    }
    else if ((env.BRANCH_NAME == null) && (repo)) {
        git url: repo, branch: branch
    }
    else {
        error 'buildPlugin must be used as part of a Multibranch Pipeline *or* a `repo` argument must be provided'
    }
}

Object runWithJava(String command, String jdk = 8, List<String> extraEnv = null) {
    String jdkTool = "jdk${jdk}"
    List<String> env = [
        "JAVA_HOME=${tool jdkTool}",
        'PATH+JAVA=${JAVA_HOME}/bin',
    ]
    if (extraEnv != null) {
        env.addAll(extraEnv)
    }

    withEnv(env) {
        if (isUnix()) {
            sh command
        } else {
            bat command
        }
    }
}

Object withErrorHandlers(int timeout, Closure body) {
    timeout(timeout) {
        timestamps {
            try {
                body()
            } catch (Exception ex) {
                echo "Caught exception: ${ex}"
                throw ex // Propagate it
            }
        }
    }
}


