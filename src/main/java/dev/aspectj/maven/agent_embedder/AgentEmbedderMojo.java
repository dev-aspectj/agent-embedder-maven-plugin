package dev.aspectj.maven.agent_embedder;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static dev.aspectj.maven.agent_embedder.JavaAgentLauncher.*;
import static dev.aspectj.maven.tools.ZipFileSystemTool.getZipFS;

/**
 * Embeds one or more java agents into the module's main artifact
 * <p>
 * Use this goal, if (and only if) you have an <b>executable JAR</b> (with a {@code Main-Class} attribute in its
 * manifest) and wish to <b>run a java agent automatically</b> when running the JAR with {@code java -jar my.jar} on
 * <b>JRE 9+</b>.
 * <p>
 * Normally, you would need an additional {@code -javaagent:/path/to/agent.jar} JVM argument, but since Java 9 there is
 * the <a href="https://docs.oracle.com/javase/9/docs/api/java/lang/instrument/package-summary.html">
 * {@code Launcher-Agent-Class} mechanism</a>. I.e., even though this plugin works just fine on Java 8, you do need to
 * launch the modified executable JAR on JRE 9+ to enjoy the benefits of this JVM feature.
 * <p>
 * The {@link #javaAgents} specified for this goal will be unpacked from their JARs and embedded into the main
 * artifact's root directory to make them visible to the java agent classloader during runtime. JVM classloaders cannot
 * load agents from nested JARs.
 * <p>
 * The main artifact is expected to exist already when executing this goal. Typically, the Maven module already uses
 * another plugin creating a build artifact during the {@code package} phase, which is why this goal by default also
 * runs in the same phase. Make sure to configure this plugin to run <i>after</i> the plugin creating the main artifact,
 * so you have something for it to operate on. This can be done most easily by simply listing both plugins in the POM
 * file in the desired chronological execution order, if they are to run in the same phase. Another option would be to
 * let them run in different phases, making sure that this plugin runs later than the one creating the artifact. We
 * recommend to stick to convention over configuration and use the {@code package} phase. For example:
 * <pre>{@code
 * <!-- Create executable Spring Boot fat JAR -->
 * <plugin>
 *   <groupId>org.springframework.boot</groupId>
 *   <artifactId>spring-boot-maven-plugin</artifactId>
 * </plugin>
 * <!-- Embed Java agent(s) for automatic execution -->
 * <plugin>
 *   <groupId>dev.aspectj</groupId>
 *   <artifactId>agent-embedder-maven-plugin</artifactId>
 *   <executions>
 *     <execution>
 *       <id>embed-agents</id>
 *       <goals>
 *         <goal>embed</goal>
 *       </goals>
 *       <configuration>
 *         <javaAgents>
 *           <!-- ... -->
 *         </javaAgents>
 *         <removeEmbeddedAgents>true</removeEmbeddedAgents>
 *       </configuration>
 *     </execution>
 *   </executions>
 * </plugin>
 * }</pre>
 * <p>
 * Unique features not offered by the Java 9+ {@code Launcher-Agent-Class} mechanism:
 * <ul>
 *   <li>
 *     Via {@code Launcher-Agent-Class}, the JVM only supports a single agent for auto-start, not multiple ones.
 *     Multiple agents can only be specified on the JVM command line. But this plugin installs its own launcher agent,
 *     which in turn is capable of starting <b>multiple java agents</b>.
 *   </li>
 *   <li>
 *     {@code Launcher-Agent-Class} also does not support <b>agent option strings</b> like the JVM command line does.
 *     This plugin, however, does support agent arguments via its launcher agent. See the {@link #javaAgents} section
 *     for more details.
 *   </li>
 * </ul>
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
  public static final String HEADER_AGENT_CLASS = "Agent-Class";

  /**
   * Host file system the mojo works on. Override for testing.
   */
  protected FileSystem hostFS = FileSystems.getDefault();

  /**
   * Java agents to embed into the main artifact
   * <p>
   * Class {@link JavaAgentInfo} describes the properties available for describing artifacts. The following properties
   * are available:
   * <ul>
   *   <li>
   *     {@code groupId}, {@code artifactId}, {@code classifier}: A java agent's Maven coordinates (without
   *     {@code version}), which are used to match a dependency declared for the module executing the plugin. If for any
   *     reason declaring the agent JAR a dependency is not an option, which should rarely be the case, see
   *     {@code agentPath} for a possible workaround.
   *   </li>
   *   <li>
   *     {@code agentClass}: The agent class containing a {@code premain} launcher method. This property is optional,
   *     because by default the plugin extracts the value from the agent JAR's {@code Agent-Class} manifest attribute.
   *     In the rare occasion that you wish to override the manifest value (e.g., the manifest does not exist or does
   *     not contain the correct value), set this property.
   *   </li>
   *   <li>
   *     {@code agentArgs}: An optional argument string for the java agent. When using {@code -javaagent} on the JVM
   *     command line, an argument string is specified after an equals sign, e.g.
   *     {@code -javaagent:/path/to/agent.jar=option1=one,option2=two}. This simply maps to an {@code agentArgs} value
   *     of {@code option1=one,option2=two}.
   *   </li>
   *   <li>
   *     {@code agentPath}: Usually, the agent path is inferred from the corresponding dependency described by
   *     configuration values {@code groupId}, {@code artifactId}, {@code classifier}. This works for regular
   *     dependencies as well as system-scoped ones. But maybe, you have a special case where e.g. the agent JAR is
   *     stored in a libraries directory checked into the project's SCM (source code management) system. <i>(Please,
   *     avoid working like that!)</i> Then, you can specify {@code agentPath} to point there. Another case is that you
   *     know the path of a nested agent JAR inside the main artifact, e.g. <i>BOOT-INF/lib/agent.jar</i>, and for some
   *     weird reason the JAR got there without being a dependency. Again, you can specify {@code agentPath} to point
   *     there. The plugin will find and unpack the JAR from there.
   *   </li>
   * </ul>
   * Here is an example for two agents to be embedded into the executable JAR, one of them also taking an option string:
   * <pre>{@code
   * <javaAgents>
   *   <agent>
   *     <groupId>org.aspectj</groupId>
   *     <artifactId>aspectjweaver</artifactId>
   *   </agent>
   *   <agent>
   *     <groupId>dev.aspectj</groupId>
   *     <artifactId>remove-final-agent</artifactId>
   *     <!-- Optional parameter, overriding agent manifest value -->
   *     <agentClass>dev.aspectj.agent.NonManifestRemoveFinalAgent</agentClass>
   *     <agentArgs>dev.aspectj.FirstComponent,dev.aspectj.SecondComponent</agentArgs>
   *   </agent>
   * </javaAgents>
   * }</pre>
   * This is an agent with dummy Maven coordinates and an agent path relative to the module base directory:
   * <pre>
   * &lt;javaAgents&gt;
   *   &lt;agent&gt;
   *     &lt;groupId&gt;dummy&lt;/groupId&gt;
   *     &lt;artifactId&gt;dummy&lt;/artifactId&gt;}
   *     &lt;agentPath&gt;${project.basedir}/lib/agent.jar&lt;/agentClass&gt;
   *   &lt;/agent&gt;
   * &lt;/javaAgents&gt;
   * </pre>
   * This is an agent with dummy Maven coordinates and an agent path inside the executable JAR, plus an option string:
   * <pre>{@code
   * <javaAgents>
   *   <agent>
   *     <groupId>dummy</groupId>
   *     <artifactId>dummy</artifactId>
   *     <agentPath>BOOT-INF/lib/agent.jar</agentClass>
   *     <agentArgs>option1=one,option2=two</agentArgs>
   *   </agent>
   * </javaAgents>
   * }</pre>
   */
  // TODO: In the second code block above, the curly brace derails formatting in Maven Site. Use inline "{@code ...}"
  //       instead of escaped '<' and '>' characters after https://issues.apache.org/jira/browse/MPLUGIN-513 fix.
  @Parameter(required = true)
  protected List<JavaAgentInfo> javaAgents;

  /**
   * Remove nested agent JARs from the executable JAR after unpacking their contents into the executable JAR
   * <p>
   * Some executable JARs contain nested dependency JARs. For example, Spring Boot executable JARs contain some or all
   * of their classpath dependencies in folder <i>BOOT-INF/lib</i>, from where they are loaded using a special
   * classloader that can read nested JARs. Agent JARs defined as dependencies, e.g. <i>aspectjweaver-x.y.z.jar</i>, can
   * also be found there, if the user did not exclude them during the build. After having expanded an agent JAR into the
   * containing JAR's root folder, the classes exist twice in the same JAR - unpacked and as a nested JAR. This is not
   * necessarily a big problem, but bloats the JAR.
   * <p>
   * This option, if active, makes the plugin search for nested JARs matching the names of artifacts described by
   * {@code javaAgents}. For each agent, the first nested JAR found is deleted.
   */
  @Parameter(required = true, defaultValue = "false")
  protected boolean removeEmbeddedAgents;

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  protected MavenProject project;

  @SuppressWarnings("unused")  // Keep for future use
  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  protected MavenSession session;

  @SuppressWarnings("unused")  // Keep for future use
  @Component
  protected MavenProjectHelper projectHelper;

  public void execute() throws MojoExecutionException {
    if (javaAgents == null || javaAgents.isEmpty()) {
      getLog().warn("List of java agents to embed is empty, skipping execution");
      return;
    }
    String artifactJarLocation = adjustPathSeparatorToHostFS(project.getArtifact().getFile().getPath(), hostFS);
    Path artifactPath = hostFS.getPath(artifactJarLocation);
    try (FileSystem jarFS = getZipFS(artifactPath, false)) {
      if (jarFS == null)
        throw new MojoExecutionException("Cannot open artifact JAR file");
      embedLauncherAgent(jarFS);
      getLog().info("Embedding java agents");
      for (JavaAgentInfo agent : javaAgents) {
        String agentJarLocation = project.getArtifacts().stream()
          .filter(agent::matchesArtifact)
          .distinct()
          .map(artifact -> adjustPathSeparatorToHostFS(artifact.getFile().getPath(), hostFS))
          .findFirst()
          .orElse(agent.getAgentPath());
        if (agentJarLocation == null)
          throw new MojoExecutionException("Java agent JAR for " + agent + " not found");
        getLog().info("Processing java agent " + agentJarLocation);
        unpackAgentJar(agent, jarFS, agentJarLocation);
      }
      new ManifestUpdater(jarFS).update();
    }
    catch (IOException | NoExecutableJarException e) {
      throw new MojoExecutionException("Error while embedding java agents", e);
    }
  }

  protected void embedLauncherAgent(FileSystem jarFS) throws IOException, MojoExecutionException {
    String agentLauncherClassName = JavaAgentLauncher.class.getName();
    String resourceName = agentLauncherClassName.replace('.', '/') + ".class";
    Path targetPath = jarFS.getPath(resourceName);
    Files.createDirectories(targetPath.getParent());
    try (
      InputStream input = JavaAgentLauncher.class.getClassLoader().getResourceAsStream(resourceName);
      OutputStream output = Files.newOutputStream(targetPath, StandardOpenOption.CREATE)
    ) {
      if (input == null)
        throw new MojoExecutionException("Cannot find/open launcher agent resource '" + resourceName + "'");
      byte[] buffer = new byte[4096];
      int bytesRead;
      while ((bytesRead = input.read(buffer)) != -1)
        output.write(buffer, 0, bytesRead);
    }
  }

  /**
   * Adjusts path separators to the ones expected on the target file system.
   * <p>
   * This mojo uses, wherever possible, {@link java.nio Java NIO} classes such as {@link Path} and {@link FileSystem}
   * for maximum flexibility and easy testability. E.g., it is possible to override the default value of {@link #hostFS}
   * and let the mojo operate in memory instead of on disk. In theory, it can happen that {@link #hostFS} paths use a
   * path separator different from the OS platform standard used by {@link File}. When interacting with the Maven API,
   * however, we sometimes have no other choice than to process {@link File} instances. In those cases, it can be
   * necessary to convert paths to the target file system.
   *
   * @param path     OS-specific path, usually derived from a {@link File}
   * @param targetFS target file system with possibly different path separator
   *
   * @return adjusted path using separators compatible with the target file system
   */
  protected String adjustPathSeparatorToHostFS(String path, FileSystem targetFS) {
    final char toSeparator = targetFS.getSeparator().charAt(0);
    final char fromSeparator = toSeparator == '/' ? '\\' : '/';
    return path.replace(fromSeparator, toSeparator);
  }

  protected void unpackAgentJar(JavaAgentInfo agentInfo, FileSystem jarFS, String agentPath) throws IOException, MojoExecutionException {
    Path agentJarPath = hostFS.getPath(agentPath);
    final boolean externalJarFound = Files.exists(agentJarPath);
    Path embeddedAgentJarPath = null;

    // Search for embedded agent JAR, if removal is requested or external JAR does not exist
    if (removeEmbeddedAgents || !externalJarFound) {
      embeddedAgentJarPath = jarFS.getPath(agentPath);
      // If embedded agent JAR is not found at exact path, search whole JAR for file name
      if (!Files.exists(embeddedAgentJarPath)) {
        Path agentFileName = embeddedAgentJarPath.getFileName();
        try (
          Stream<Path> files = Files.find(
            jarFS.getPath("/"), Integer.MAX_VALUE,
            (path, basicFileAttributes) -> path.getNameCount() > 0 && path.getFileName().equals(agentFileName)
          )
        ) {
          embeddedAgentJarPath = files.findFirst().orElse(null);
        }
      }
    }

    if (!externalJarFound)
      agentJarPath = embeddedAgentJarPath;
    Objects.requireNonNull(agentJarPath, "Java agent JAR not found");

    //noinspection RedundantCast: newFileSystem is overloaded in more recent JDKs
    try (FileSystem javaAgentFS = getZipFS(agentJarPath, false)) {
      Objects.requireNonNull(javaAgentFS);
      configureJavaAgentClass(agentInfo, agentJarPath, javaAgentFS);

      try (
        Stream<Path> files = Files.find(
          javaAgentFS.getPath("/"), Integer.MAX_VALUE,
          // Do not overwrite existing files, especially META-INF/MANIFEST.MF
          (path, basicFileAttributes) -> !Files.exists(jarFS.getPath(path.toString()))
        )
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

  /**
   * Determine and configure the java agent class for a given agent.
   * <p>
   * By default, read it from the agent info given in the plugin configuration. If unset, try to extract it from the
   * agent JAR's manifest, reading its {@code Agent-Class} attribute. If neither of the two is specified, throw a
   * {@link MojoExecutionException}.
   * <p>
   * Note: The method does not check if the determined class actually exists in the agent JAR.
   */
  protected void configureJavaAgentClass(JavaAgentInfo agentInfo, Path agentJarPath, FileSystem javaAgentFS)
    throws IOException, MojoExecutionException
  {
    getLog().debug("Configuring java agent class for " + agentInfo);

    getLog().debug("Reading agent manifest from path " + agentJarPath);
    Manifest javaAgentManifest = new Manifest(Files.newInputStream(javaAgentFS.getPath("/" + MANIFEST_PATH)));

    String manifestAgentClass = javaAgentManifest.getMainAttributes().getValue(HEADER_AGENT_CLASS);
    manifestAgentClass = manifestAgentClass == null ? "" : manifestAgentClass.trim();
    getLog().debug("Agent class from manifest: " + manifestAgentClass);

    agentInfo.setAgentClass(agentInfo.getAgentClass() == null ? "" : agentInfo.getAgentClass().trim());
    getLog().debug("Agent class from plugin configuration: " + agentInfo.getAgentClass());

    if (manifestAgentClass.isEmpty()) {
      if (agentInfo.getAgentClass().isEmpty())
        throw new MojoExecutionException(
          "Agent class for " + agentInfo + " neither configured nor found in agent manifest"
        );
      getLog().warn(
        "Agent class name not found in agent manifest, using configured value '" +
          agentInfo.getAgentClass() + "'. Attention: JAR does not seem to be a regular java agent."
      );
    }
    else if (agentInfo.getAgentClass().isEmpty()) {
      getLog().debug("Using agent class '" + manifestAgentClass + "' found in manifest");
      agentInfo.setAgentClass(manifestAgentClass);
    }
    else {
      getLog().debug("Using agent class '" + agentInfo.getAgentClass() + "' found in plugin configuration");
    }
  }

  public class ManifestUpdater {
    public static final String MANIFEST_HEADER_MAIN_CLASS = "Main-Class";
    public static final String MANIFEST_HEADER_LAUNCHER_AGENT = "Launcher-Agent-Class";

    private final Path manifestPath;
    private final Manifest manifest;

    public ManifestUpdater(FileSystem fileSystem) throws IOException, NoExecutableJarException {
      manifestPath = fileSystem.getPath(MANIFEST_PATH);
      manifest = getExecutableJarManifest();
    }

    private Manifest getExecutableJarManifest() throws IOException, NoExecutableJarException {
      if (!Files.exists(manifestPath))
        throw new NoExecutableJarException("missing manifest file '" + MANIFEST_PATH + "'");
      Manifest manifest = new Manifest();
      try (InputStream inputStream = Files.newInputStream(manifestPath)) {
        manifest.read(inputStream);
      }
      Attributes mainAttributes = manifest.getMainAttributes();
      if (mainAttributes.getValue(MANIFEST_HEADER_MAIN_CLASS) == null)
        throw new NoExecutableJarException("missing manifest attribute '" + MANIFEST_HEADER_MAIN_CLASS + "'");
      return manifest;
    }

    public void update() throws IOException {
      addLauncherAgentAttribute();
      addEmbeddedAgentAttributes();
      writeManifest();
    }

    private void addLauncherAgentAttribute() {
      Attributes mainAttributes = manifest.getMainAttributes();
      String existingLauncherAgent = mainAttributes.getValue(MANIFEST_HEADER_LAUNCHER_AGENT);
      if (existingLauncherAgent != null)
        getLog().warn(
          "Overwriting existing manifest attribute '" +
            MANIFEST_HEADER_LAUNCHER_AGENT + ": " + existingLauncherAgent + "'"
        );
      getLog().debug(
        "Setting manifest attribute '" +
          MANIFEST_HEADER_LAUNCHER_AGENT + ": " + JavaAgentLauncher.class.getName() + "'"
      );
      mainAttributes.putValue(MANIFEST_HEADER_LAUNCHER_AGENT, JavaAgentLauncher.class.getName());
    }

    private void addEmbeddedAgentAttributes() {
      Attributes agentAttributes = new Attributes();
      agentAttributes.putValue("Agent-Count", String.valueOf(javaAgents.size()));
      int agentIndex = 0;
      for (JavaAgentInfo agent : javaAgents) {
        agentIndex++;
        agentAttributes.putValue(AGENT_CLASS + agentIndex, agent.getAgentClass());
        if (agent.getAgentArgs() != null)
          agentAttributes.putValue(AGENT_ARGS + agentIndex, agent.getAgentArgs());
      }
      manifest.getEntries().put(AGENT_ATTRIBUTES_GROUP, agentAttributes);
    }

    private void writeManifest() throws IOException {
      Files.delete(manifestPath);
      try (OutputStream manifestOut = Files.newOutputStream(manifestPath)) {
        manifest.write(manifestOut);
      }
    }
  }

  public static class NoExecutableJarException extends Exception {
    private static final String ERROR_MESSAGE = "Target JAR is not executable. Reason: %s. " +
      "Therefore, it does not make sense to embed any java agents.";

    public NoExecutableJarException(String reason) {
      super(String.format(ERROR_MESSAGE, reason));
    }
  }

}
