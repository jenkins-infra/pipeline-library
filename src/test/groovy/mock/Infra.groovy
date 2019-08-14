package mock

/**
 * Mock Infra step
 */
class Infra implements Serializable {

  private final boolean result
  public Infra(boolean result) { this.result = result }
  public Infra() { this.result = false }
  public void checkout() { }
  public void checkout(repo) { }
  public String retrieveMavenSettingsFile(String location) { return location }
  public String runWithMaven(String cmd) { return cmd }
  public String runMaven(mvn, jdk, foo, settings) { return 'OK' }
  public String runMaven(mvn, jdk, foo, settings, toolEnv) { return mvn }
  public String runWithJava(command, jdk, foo, toolEnv) { return command }
  public boolean isTrusted() { return result }
  public void maybePublishIncrementals() { }
}
