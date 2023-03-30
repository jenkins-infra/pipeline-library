package mock

/**
 * Mock Infra step
 */
class Infra implements Serializable {

  private boolean trusted
  private boolean release
  private boolean infra
  private boolean buildError

  public void checkoutSCM(String repo = null) { }

  public Object withArtifactCachingProxy(Boolean useArtifactCachingProxy, Closure body) {
    if (buildError) {
      throw new RuntimeException('build error')
    } else {
      return body
    }
  }

  public Object runMaven(List<String> options, String jdk = null, List<String> extraEnv = null, Boolean addToolEnv = null, Boolean useArtifactCachingProxy = true) {
    def command = "mvn ${options.join(' ')}"
    return runWithMaven(command, jdk, extraEnv, addToolEnv)
  }

  public Object runWithMaven(String command, String jdk = null, List<String> extraEnv = null, Boolean addToolEnv = null) {
    return runWithJava(command, jdk, extraEnv, addToolEnv)
  }

  public Object runWithJava(String command, String jdk = null, List<String> extraEnv = null, Boolean addToolEnv = null) {
    if (buildError) {
      throw new RuntimeException('build error')
    } else {
      return command
    }
  }

  public boolean isTrusted() {
    return trusted
  }

  public boolean isRelease() {
    return release
  }

  public boolean isInfra() {
    return infra
  }

  public void maybePublishIncrementals() { }

  void publishDeprecationCheck(String deprecationSummary, String deprecationMessage) { }
}
