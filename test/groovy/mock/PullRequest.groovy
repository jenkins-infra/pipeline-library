package mock

/**
 * Mock PullRequest
 */
class PullRequest implements Serializable {
  String comment
  def labels

  public PullRequest(def labels = []) {
    this.labels = labels
  }

  public comment(String comment) {
    this.comment = comment
  }

  public def labels() {
    return this.labels
  }
}
