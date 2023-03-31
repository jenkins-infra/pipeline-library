package mock

/**
 * Mock PullRequest
 */
class PullRequest implements Serializable {
  def labels

  public PullRequest(def labels = []) {
    this.labels = labels
  }

  public def labels() {
    return this.labels
  }
}
