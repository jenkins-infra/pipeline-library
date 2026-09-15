package mock

/**
 * Mock scm, mimicking a single-remote Git SCM
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
