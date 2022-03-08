import java.util.*
//import java.text.*
import org.json.*
import io.jsonwebtoken.*
import org.apache.commons.codec.binary.Base64
// import com.google.gson.JsonObject
import groovy.json.JsonSlurper
import wslite.rest.*

def call(userConfig = [:]) {
  def defaultConfig = [
    githubAppPrivateKeyB64: '', // Private key (in PEM format, base64 encoded) of the Github App to use
    githubAppId: 0, // Identifier of the Github App to use
    githubOrgName: 'jenkins-infra', // Github organisation name where the Github App to use is installed
  ]

  // Merging the 2 maps - https://blog.mrhaki.com/2010/04/groovy-goodness-adding-maps-to-map_21.html
  final Map finalConfig = defaultConfig << userConfig

  // Generate a JWT
  String jwtToken = ''
  int now = (new Date().getTime() / 1000).round(0)
  int exp = now + (10 * 60)
  try {
    String jwtToken = Jwts.builder()
        .setHeaderParam('typ', 'JWT')
        .setPayload("{\"iat\": ${iat}, \"exp\": ${exp}, \"iss\": ${id}}")
        .signWith(SignatureAlgorithm.HS256, privateKeyB64)
        .compact();
  } catch (Exception ex) {
    echo ex.getMessage()
    // TODO: fail the pipeline/step here
    return
  }

  echo "JWT: ${jwtToken}"

  int installationId = 0
  fetch("https://api.github.com/app/installations").findAll { it?.account?.login == githubOrgName }.each { installation ->
    installationId = installation.id
  }
  if (installationId > 0) {
    def client = new RESTClient("https://api.github.com")
    def path = "/app/installations/${installationId}/access_tokens"
    def response = client.post(path: path, headers: ["Authorization": "Bearer ${jwtToken}"]) {
        type ContentType.JSON
    }
    echo response.json?.toString()


  } else {
    echo "Error: no Github App installation for the organization ${githubOrgName}"
  }

 
}

// Fetch a Github API URL and parses the result as JSON
def fetch(jwt, addr, params = [:]) {
  def auth = "Bearer ${jwt}"
  def json = new JsonSlurper()
  return json.parse(addr.toURL().newReader(requestProperties: ["Authorization": auth.toString(), "Accept": "application/json"]))
}
