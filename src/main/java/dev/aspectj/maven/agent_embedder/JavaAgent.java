package dev.aspectj.maven.agent_embedder;

import org.apache.maven.artifact.Artifact;

import java.util.Objects;

public class JavaAgent {
  private String groupId;
  private String artifactId;
  private String classifier;
  private String agentClass;
  private String agentPath;

  @SuppressWarnings("unused")
  public JavaAgent() {}

  public JavaAgent(String groupId, String artifactId, String classifier, String agentClass, String agentPath) {
    this.groupId = groupId;
    this.artifactId = artifactId;
    this.classifier = classifier;
    this.agentClass = agentClass;
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

  public String getAgentPath() {
    return agentPath;
  }

  /**
   * Checks if the Java agent matches a given Maven artifact
   *
   * @param artifact Maven artifact
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
    if (this == o) return true;
    if (!(o instanceof JavaAgent)) return false;
    JavaAgent javaAgent = (JavaAgent) o;
    return Objects.equals(groupId, javaAgent.groupId) && Objects.equals(artifactId, javaAgent.artifactId) && Objects.equals(classifier, javaAgent.classifier) && Objects.equals(agentClass, javaAgent.agentClass) && Objects.equals(agentPath, javaAgent.agentPath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(groupId, artifactId, classifier, agentClass, agentPath);
  }

  @Override
  public String toString() {
    return "JavaAgent(" +
      "groupId=" + groupId +
      ", artifactId=" + artifactId +
      ", classifier=" + classifier +
      ", agentClass=" + agentClass +
      ", agentPath=" + agentPath +
      ')';
  }
}
