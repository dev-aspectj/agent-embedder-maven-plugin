package dev.aspectj.maven.agent_embedder;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Embeds one or more java agents into the module's main artifact
 */
@Mojo(
  name = "embed",
  defaultPhase = LifecyclePhase.PACKAGE,
  threadSafe = true,
  requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
  requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME
)
public class AgentEmbedderMojo extends AbstractMojo {
  public static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";
  public static final String MANIFEST_HEADER_LAUNCHER_AGENT = "Launcher-Agent-Class";

  /**
   * Host file system the mojo works on. Override for testing.
   */
  protected FileSystem hostFS = FileSystems.getDefault();

  /**
   * Java agents to embed into the main artifact
   */
  @Parameter(required = true)
  protected List<JavaAgent> javaAgents;

  /**
   * Should agent JARs described by {@code javaAgents}, if found embedded inside the fat JAR, be deleted after expanding
   * the agent classes into the root directory of the fat JAR?
   * <p>
   * For example: Spring Boot executable JARs contain all classpath dependencies in folder <i>BOOT-INF/lib</i>, from
   * where normally they are loaded using a special classloader that can read embedded JARs. Agent JARs defined as
   * dependencies, e.g. <i>aspectweaver-x.y.z.jar</i>, can also be found there. After having expanded an agent JAR into
   * the root folder, the classes exist twice in the same JAR, which is not a big problem, but bloats the JAR.
   */
  @Parameter(required = true, defaultValue = "false")
  protected boolean removeEmbeddedAgents;

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  protected MavenProject project;
  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  protected MavenSession session;
  @Component
  protected MavenProjectHelper projectHelper;

  public void execute() throws MojoExecutionException {
    getLog().info("Embedding java agents");
    for (JavaAgent agent : javaAgents) {
      String agentJarLocation = project.getArtifacts().stream()
        .filter(agent::matchesArtifact)
        .distinct()
        .map(artifact -> adjustPathSeparatorToHostFS(artifact.getFile().getPath(), hostFS))
        .findFirst()
        .orElse(agent.getAgentPath());
      if (agentJarLocation == null)
        throw new MojoExecutionException("Java agent JAR for " + agent + " not found");
      getLog().info("Processing java agent " + agentJarLocation);

      String artifactJarLocation = adjustPathSeparatorToHostFS(project.getArtifact().getFile().getPath(), hostFS);
      Path artifactPath = hostFS.getPath(artifactJarLocation);

      //noinspection RedundantCast: newFileSystem is overloaded in more recent JDKs
      try (FileSystem jarFS = FileSystems.newFileSystem(artifactPath, (ClassLoader) null)) {
        addLauncherAgentToManifest(jarFS, agent.getAgentClass());
        unpackAgentJar(jarFS, agentJarLocation);
      }
      catch (IOException e) {
        throw new MojoExecutionException("Error while processing java agent " + agent, e);
      }
    }
  }

  /**
   * Adjusts path separators to the ones expected on the target file system.
   * <p>
   * This mojo uses, wherever possible, {@link java.nio Java NIO} classes such as {@link Path} and {@link FileSystem} for maximum
   * flexibility and easy testability. E.g., it is possible to override the default value of {@link #hostFS} and let the
   * mojo operate in memory instead of on disk. In theory, it can happen that {@link #hostFS} paths use a path separator
   * different from the OS platform standard used by {@link File}. When interacting with the Maven API, however, we
   * sometimes have no other choice than to process {@link File} instances. In those cases, it can be necessary to
   * convert paths to the target file system.
   *
   * @param path     OS-specific path, usually derived from a {@link File}
   * @param targetFS target file system with possibly different path separator
   * @return adjusted path using separators compatible with the target file system
   */
  protected String adjustPathSeparatorToHostFS(String path, FileSystem targetFS) {
    return File.separator.equals(targetFS.getSeparator())
      ? path
      : path.replace(File.separator, targetFS.getSeparator());
  }

  protected void addLauncherAgentToManifest(FileSystem jarFS, String agentClass) throws IOException, MojoExecutionException {
    Path manifestPath = jarFS.getPath(MANIFEST_PATH);
    if (!Files.exists(manifestPath))
      throw new MojoExecutionException(
        "Target JAR does not contain " + MANIFEST_PATH + ", i.e. it cannot be an executable JAR. " +
          "Hence, it does not make sense to embed a java agent either."
      );
    Manifest manifest;
    try (InputStream manifestIn = Files.newInputStream(manifestPath)) {
      manifest = new Manifest(manifestIn);
    }
    Attributes mainAttributes = manifest.getMainAttributes();
    if (mainAttributes.containsKey(MANIFEST_HEADER_LAUNCHER_AGENT))
      getLog().warn("Manifest already contains unique attribute '" + MANIFEST_HEADER_LAUNCHER_AGENT + "', overwriting");
    String manifestLauncherAgentEntry = MANIFEST_HEADER_LAUNCHER_AGENT + ": " + agentClass;
    getLog().debug("Setting manifest attribute '" + manifestLauncherAgentEntry + "'");
    mainAttributes.put(new Attributes.Name(MANIFEST_HEADER_LAUNCHER_AGENT), agentClass);
    try (OutputStream manifestOut = Files.newOutputStream(manifestPath)) {
      manifest.write(manifestOut);
    }
  }

  protected void unpackAgentJar(FileSystem jarFS, String agentPath) throws IOException, MojoExecutionException {
    Path agentJarPath = hostFS.getPath(agentPath);
    final boolean externalJarFound = Files.exists(agentJarPath);
    Path embeddedAgentJarPath = null;

    // Search for embedded agent JAR, if removal is requested or external JAR does not exist
    if (removeEmbeddedAgents || !externalJarFound) {
      embeddedAgentJarPath = jarFS.getPath(agentPath);
      // If embedded agent JAR is not found at exact path, search whole JAR for file name
      if (!Files.exists(embeddedAgentJarPath)) {
        Path agentFileName = embeddedAgentJarPath.getFileName();
        try (Stream<Path> files = Files.find(
          jarFS.getPath("/"), Integer.MAX_VALUE,
          (path, basicFileAttributes) -> path.getNameCount() > 0 && path.getFileName().equals(agentFileName))
        ) {
          embeddedAgentJarPath = files.findFirst().orElse(null);
        }
      }
    }

    if (!externalJarFound)
      agentJarPath = embeddedAgentJarPath;
    Objects.requireNonNull(agentJarPath, "Java agent JAR not found");

    //noinspection RedundantCast: newFileSystem is overloaded in more recent JDKs
    try (FileSystem javaAgentFS = FileSystems.newFileSystem(agentJarPath, (ClassLoader) null)) {
      try (
        Stream<Path> files = Files.find(
          javaAgentFS.getPath("/"), Integer.MAX_VALUE,
          (path, basicFileAttributes) -> !Files.exists(jarFS.getPath(path.toString())))
      ) {
        files.forEach(path -> {
          getLog().debug("Unpacking: " + path);
          try {
            Files.copy(path, jarFS.getPath(path.toString()));
          }
          catch (IOException e) {
            throw new RuntimeException("Problem when unpacking java agent JAR", e);
          }
        });
      }
      catch (RuntimeException e) {
        // Catch and re-wrap checked exception from 'forEach' block above
        if ("Problem when unpacking java agent JAR".equals(e.getMessage()) && e.getCause() instanceof IOException)
          throw new MojoExecutionException(e.getMessage(), e.getCause());
        else
          throw e;
      }
    }

    if (removeEmbeddedAgents && embeddedAgentJarPath != null && Files.exists(embeddedAgentJarPath)) {
      getLog().info("Removing embedded java agent: " + embeddedAgentJarPath);
      Files.delete(embeddedAgentJarPath);
    }
  }
}
