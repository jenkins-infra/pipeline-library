package mock

/**
 * Mock scm, mimicking a single-remote Git SCM (jenkins.scm.api.SCMSource / GitSCM#getUserRemoteConfigs())
 */
class Scm implements Serializable {
  private final String url

  public Scm(String url) {
    this.url = url
  }

  List<Scm> getUserRemoteConfigs() {
    return [this]
  }

  String getUrl() {
    return url
  }
}
