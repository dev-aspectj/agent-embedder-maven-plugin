package dev.aspectj.maven.agent_embedder;

import org.apache.maven.artifact.Artifact;

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Objects;

public class JavaAgentInfo {
  private String groupId;
  private String artifactId;
  private String classifier;
  private String agentClass;
  private String agentArgs;
  private String agentPath;

  @SuppressWarnings("unused")
  public JavaAgentInfo() {}

  public JavaAgentInfo(String groupId, String artifactId, String classifier, String agentClass, String agentArgs, String agentPath) {
    this.groupId = groupId;
    this.artifactId = artifactId;
    this.classifier = classifier;
    this.agentClass = agentClass;
    this.agentArgs = agentArgs;
    this.agentPath = agentPath;
  }

  public String getGroupId() {
    return groupId;
  }

  public String getArtifactId() {
    return artifactId;
  }

  public String getClassifier() {
    return classifier;
  }

  public String getType() {
    return "jar";
  }

  public String getAgentClass() {
    return agentClass;
  }

  /**
   * In contrast to other agent info properties, the agent class needs to be updateable, because the property is
   * optional and can be determined by scanning the agent's manifest, extracting its {@code Agent-Class} attribute in
   * the process. See {@link AgentEmbedderMojo#configureJavaAgentClass(JavaAgentInfo, Path, FileSystem)}.
   *
   * @param agentClass java agent main class
   */
  public void setAgentClass(String agentClass) {
    this.agentClass = agentClass;
  }

  public String getAgentArgs() {
    return agentArgs;
  }

  public String getAgentPath() {
    return agentPath;
  }

  /**
   * Checks if the Java agent matches a given Maven artifact
   *
   * @param artifact Maven artifact
   *
   * @return true, if the two elements match, false otherwise
   */
  public boolean matchesArtifact(Artifact artifact) {
    // Compare type first, because it is a fixed value
    return Objects.equals(getType(), artifact.getType()) &&
      Objects.equals(getGroupId(), artifact.getGroupId()) &&
      Objects.equals(getArtifactId(), artifact.getArtifactId()) &&
      Objects.equals(getClassifier(), artifact.getClassifier());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (!(o instanceof JavaAgentInfo))
      return false;
    JavaAgentInfo javaAgent = (JavaAgentInfo) o;
    return Objects.equals(groupId, javaAgent.groupId) &&
      Objects.equals(artifactId, javaAgent.artifactId) &&
      Objects.equals(classifier, javaAgent.classifier) &&
      Objects.equals(agentClass, javaAgent.agentClass) &&
      Objects.equals(agentArgs, javaAgent.agentArgs) &&
      Objects.equals(agentPath, javaAgent.agentPath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(groupId, artifactId, classifier, agentClass, agentArgs, agentPath);
  }

  @Override
  public String toString() {
    return "JavaAgentInfo(" +
      "groupId=" + groupId +
      ", artifactId=" + artifactId +
      ", classifier=" + classifier +
      ", agentClass=" + agentClass +
      ", agentArgs=" + agentArgs +
      ", agentPath=" + agentPath +
      ')';
  }
}
