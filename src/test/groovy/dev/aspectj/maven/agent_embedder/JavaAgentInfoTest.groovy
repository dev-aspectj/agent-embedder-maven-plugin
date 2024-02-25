package dev.aspectj.maven.agent_embedder

import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.handler.ArtifactHandler
import spock.lang.Shared
import spock.lang.Specification

import static pl.pojo.tester.api.assertion.Assertions.assertPojoMethodsFor
import static pl.pojo.tester.api.assertion.Method.*

class JavaAgentInfoTest extends Specification {
  @Shared
  final ArtifactHandler artifactHandler = Mock()

  def 'JavaAgent matches Artifact for identical groupId, artifactId, classifier, type'(String scope, String version) {
    given:
    JavaAgentInfo agentInfo = createJavaAgentInfo()
    Artifact artifact = new DefaultArtifact('dev.aspectj', 'my-artifact', version, scope, 'jar', 'my-classifier', artifactHandler)

    expect:
    agentInfo.matchesArtifact(artifact)

    where:
    [scope, version] << [
      ['compile', 'system', 'test'],
      ['1.2.3', '4.5-SNAPSHOT'],
    ].combinations()
  }

  def 'JavaAgent does not match non-jar Artifact'() {
    given:
    JavaAgentInfo agentInfo = createJavaAgentInfo()
    Artifact artifact = new DefaultArtifact('dev.aspectj', 'my-artifact', '1.2.3', 'compile', type, 'my-classifier', artifactHandler)

    expect:
    !agentInfo.matchesArtifact(artifact)

    where:
    type << ['war', 'zip', 'ear']
  }

  def 'JavaAgent does not match Artifact if groupId, artifactId, classifier are different'() {
    given:
    JavaAgentInfo agentInfo = createJavaAgentInfo()
    Artifact artifact = new DefaultArtifact(groupId, artifactId, '1.2.3', 'compile', 'jar', classifier, artifactHandler)

    expect:
    !agentInfo.matchesArtifact(artifact)

    where:
    groupId       | artifactId       | classifier
    'dev.aspectj' | 'my-artifact'    | 'other-classifier'
    'dev.aspectj' | 'other-artifact' | 'my-classifier'
    'dev.other'   | 'my-artifact'    | 'my-classifier'
  }

  def 'Maven needs default constructor for JavaAgent'() {
    expect:
    new JavaAgentInfo()
  }

  def 'JavaAgent.toString returns the expected value'() {
    given:
    JavaAgentInfo agentInfo = createJavaAgentInfo()

    expect:
    agentInfo.toString() == 'JavaAgentInfo(' +
      'groupId=dev.aspectj, artifactId=my-artifact, classifier=my-classifier, ' +
      'agentClass=dev.aspectj.MyAgent, agentArgs=my-args, agentPath=/home/me/agent.jar' +
      ')'
  }

  def 'JavaAgent type is always jar'() {
    given:
    JavaAgentInfo agentInfo = createJavaAgentInfo()

    expect:
    agentInfo.type == 'jar'
  }

  def 'check POJO methods'() {
    expect:
    assertPojoMethodsFor(JavaAgentInfo)
      .testing(CONSTRUCTOR, GETTER, /*SETTER,*/ EQUALS, HASH_CODE, TO_STRING)
      .areWellImplemented()
  }

  private static JavaAgentInfo createJavaAgentInfo() {
    return new JavaAgentInfo('dev.aspectj', 'my-artifact', 'my-classifier', 'dev.aspectj.MyAgent', 'my-args', '/home/me/agent.jar')
  }
}
