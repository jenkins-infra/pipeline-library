Object checkout(String repo = null) {
    if (env.BRANCH_NAME) {
        checkout scm
    }
    else if ((env.BRANCH_NAME == null) && (repo)) {
        git repo
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


