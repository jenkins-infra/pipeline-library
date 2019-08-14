package mock

/**
 * Mock Docker class from docker-workflow plugin.
 */
class Docker implements Serializable {
  public Image image(String id) { new Image(this, id) }
  public class Image implements Serializable {
    private Image(Docker docker, String id) {}
    public <V> V inside(String args = '', Closure<V> body) { body() }
  }
}
