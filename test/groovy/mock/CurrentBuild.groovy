package mock

/**
 * Mock currentBuild
 */
class CurrentBuild implements Serializable {
  String result
  ArrayList buildCauses

  public CurrentBuild(String result) {
    this.result = result
    this.buildCauses = []
  }

  public CurrentBuild(String result, ArrayList buildCauses) {
    this.result = result
    this.buildCauses = buildCauses
  }
}
