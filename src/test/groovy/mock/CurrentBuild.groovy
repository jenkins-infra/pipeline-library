package mock

/**
 * Mock currentBuild
 */
class CurrentBuild implements Serializable {
  String result
  public CurrentBuild(String result) { this.result = result }
}
