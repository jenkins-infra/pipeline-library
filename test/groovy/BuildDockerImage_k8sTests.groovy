import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import mock.CurrentBuild
import mock.Infra
import org.junit.Before
import org.junit.Test
import org.junit.Ignore
import static com.lesfurets.jenkins.unit.MethodCall.callArgsToString
import static org.junit.Assert.assertTrue
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertNotNull

class BuildDockerImageK8sTests extends DeclarativePipelineTest {
  static final String scriptName = "vars/buildDockerImage_k8s.groovy"
  Map env = [:]

  @Override
  @Before
  void setUp() throws Exception {
    super.setUp()

    binding.setVariable('env', env)
    binding.setProperty('scm', new String())
    binding.setProperty('mvnSettingsFile', 'settings.xml')
    binding.setProperty('infra', new Infra())

    helper.registerAllowedMethod('ansiColor', [String.class], { s -> s })

    helper.registerAllowedMethod('sh', [Map], {m->
      if (m.returnStdout) {
        cmd = m.script
        // cmd.contains is helpful to filter sh call which should fail the pipeline
        if (cmd.contains("git log -n 1 --pretty=format:'%h'")) {
            return 'abc123'
        }
        if (cmd.contains('git remote show origin')) {
          return 'git@github.com:jenkins-infra/pipeline-library.git'
        }
        if (cmd.contains('date --rfc-3339=seconds')) {
          return '2020-05-25T07:11:16+00:00'
        }
      }
    })
  }

  @Test
  @Ignore("until https://github.com/jenkinsci/JenkinsPipelineUnit/pull/220 is merged and released")
  void testCallsDockerBuild() throws Exception {
    helper.registerAllowedMethod("buildingTag", [], { false })

    def script = loadScript(scriptName)
    script("jenkins-wiki-exporter")

    def calls = helper.callStack.findAll { call ->
      call.methodName == 'sh'
    }.findAll { call ->
      callArgsToString(call).contains('img build')
    }.collect { call ->
      callArgsToString(call).replaceAll(/\s+/, ' ').trim()
    }
    //printCallStack()
    assertEquals(calls, ['img build -t jenkins4eval/jenkins-wiki-exporter --build-arg "GIT_COMMIT_REV=abc123" --build-arg "GIT_SCM_URL=git@github.com:jenkins-infra/pipeline-library.git" --build-arg "BUILD_DATE=2020-05-25T07:11:16+00:00" --label "org.opencontainers.image.source=git@github.com:jenkins-infra/pipeline-library.git" --label "org.label-schema.vcs-url=git@github.com:jenkins-infra/pipeline-library.git" --label "org.opencontainers.image.url==https://github.com/jenkins-infra/pipeline-library.git" --label "org.label-schema.url=https://github.com/jenkins-infra/pipeline-library.git" --label "org.opencontainers.image.revision=abc123" --label "org.label-schema.vcs-ref=abc123" --label "org.opencontainers.created=2020-05-25T07:11:16+00:00" --label "org.label-schema.build-date=2020-05-25T07:11:16+00:00" -f Dockerfile .'])
  }
}
