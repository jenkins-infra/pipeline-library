#!/usr/bin/env groovy

Boolean isRunningOnJenkinsInfra() {
    return env.JENKINS_URL == 'https://ci.jenkins.io/' || isTrusted()
}

Boolean isTrusted() {
    return env.JENKINS_URL == 'https://trusted.ci.jenkins.io:1443/'
}

Object withDockerCredentials(Closure body) {
    if (isTrusted()) {
        withCredentials([[$class: 'ZipFileBinding', credentialsId: 'jenkins-dockerhub', variable: 'DOCKER_CONFIG']]) {
            return body.call()
        }
    }
    else {
        echo 'Cannot use Docker credentials outside of the trusted environment'
    }
}

Object checkout(String repo = null) {
    if (env.BRANCH_NAME) {
        checkout scm
    } else if ((env.BRANCH_NAME == null) && (repo)) {
        git repo
    } else {
        error 'buildPlugin must be used as part of a Multibranch Pipeline *or* a `repo` argument must be provided'
    }
}

/**
 * Runs Maven for the specified options in the current workspace.
 * Azure settings will be added by default if running on Jenkins Infra.
 * @param jdk JDK to be used
 * @param options Options to be passed to the Maven command
 * @param extraEnv Extra environment variables to be passed when invoking the command
 * @return nothing
 */
Object runMaven(List<String> options, String jdk = 8, List<String> extraEnv = null) {
    List<String> mvnOptions = [ ]
    if (jdk.toInteger() > 7 && isRunningOnJenkinsInfra()) {
        /* Azure mirror only works for sufficiently new versions of the JDK due to Letsencrypt cert */
        def settingsXml = "${pwd tmp: true}/settings-azure.xml"
        writeFile file: settingsXml, text: libraryResource('settings-azure.xml')
        mvnOptions += "-s $settingsXml"
    }
    mvnOptions.addAll(options)
    String command = "mvn ${mvnOptions.join(' ')}"
    runWithMaven(command, jdk, extraEnv)
}

/**
 * Runs the command with Java  and Maven environment.
 * The command may be either Batch or Shell depending on the OS.
 * @param command Command to be executed
 * @param jdk JDK version to be used
 * @param extraEnv Extra environment variables to be passed
 * @return nothing
 */
Object runWithMaven(String command, String jdk = 8, List<String> extraEnv = null) {
    List<String> env = [
        "PATH+MAVEN=${tool 'mvn'}/bin"
    ]
    if (extraEnv != null) {
        env.addAll(extraEnv)
    }

    runWithJava(command, jdk, env)
}

/**
 * Runs the command with Java environment.
 * The command may be either Batch or Shell depending on the OS.
 * @param command Command to be executed
 * @param jdk JDK version to be used
 * @param extraEnv Extra environment variables to be passed
 * @return nothing
 */
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
        if (isUnix()) { // TODO JENKINS-44231 candidate for simplification
            sh command
        } else {
            bat command
        }
    }
}
