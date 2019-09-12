package mock

/**
 * Mock Infra step
 */
class Infra implements Serializable {

  private final boolean trusted
  private final boolean error
  public Infra(boolean trusted) { this(trusted, false) }
  public Infra() { this(false) }
  public Infra(boolean trusted, boolean error) {
    this.trusted = trusted
    this.error = error
  }
  public void checkout() { }
  public void checkout(repo) { }
  public String retrieveMavenSettingsFile(String location) { return location }
  public String runWithMaven(String cmd) { return cmd }
  public String runMaven(mvn) { return mvn }
  public String runMaven(mvn, jdk, foo, settings) { return 'OK' }
  public String runMaven(mvn, jdk, foo, settings, toolEnv) {
    if (this.error) {
      throw new Exception('There was a build error')
    }
    return mvn
  }
  public String runWithJava(command, jdk, foo, toolEnv) { return command }
  public boolean isTrusted() { return this.trusted }
  public void maybePublishIncrementals() { }
}
