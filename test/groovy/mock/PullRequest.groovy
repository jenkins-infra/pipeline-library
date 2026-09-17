package mock

/**
 * Mock PullRequest
 */
class PullRequest implements Serializable {
  def labels
  def head

  public PullRequest(def labels = [], def head = 'mocked-pr-head-sha') {
    this.labels = labels
    this.head = head
  }

  public def labels() {
    return this.labels
  }
}
