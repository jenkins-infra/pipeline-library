package mock

/**
 * Mock currentBuild
 */
class CurrentBuild implements Serializable {
  String result = 'SUCCESS'
  int number = 1
  def changeSets = null
  public CurrentBuild() { }
  public CurrentBuild(String result) { this.result = result }
  public CurrentBuild(int number) { this.number = number }

  public CurrentBuild(int number, boolean initializeChangeSets) {
    this.number = number
    if (initializeChangeSets) {
      List<String> fileOne = [ 'README.md', ]
      def changeSetOne = [ commitId: 'SHA-1.1', author: 'writer.1', affectedFiles: fileOne ]
      this.changeSets = [ [items:changeSetOne], ]
    }
  }
}
